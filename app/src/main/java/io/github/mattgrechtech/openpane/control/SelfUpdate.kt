package io.github.mattgrechtech.openpane.control

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import io.github.mattgrechtech.openpane.BuildConfig
import io.github.mattgrechtech.openpane.diag.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installing a new release, calmly. Nothing happens until someone asks (Home Assistant's Update
 * entity or the admin page). Then the app waits for a quiet moment (dashboard in front, nothing
 * playing), downloads the release's APK, checks its SHA-256 against the release's checksum file,
 * that it's this app, newer, and signed with the same key, and hands it to Android, which asks
 * for one confirmation on the screen. At most one attempt per release unless asked again.
 */
class SelfUpdate(private val context: Context) {

    @Volatile private var wanted = false
    @Volatile private var busy = false

    /** Asks for the latest release to be installed at the next quiet moment; null if it will be, else why not. */
    fun request(): String? {
        if (!canInstall()) {
            status = "needs permission: allow OpenPane to install apps (adb: appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow)"
            return status
        }
        wanted = true
        status = "waiting for a quiet moment (dashboard in front, nothing playing)"
        return null
    }

    /** Called about every half minute while the dashboard is in front. */
    fun tick() {
        if (!wanted || busy) return
        if (context.getSystemService(AudioManager::class.java)?.isMusicActive == true) return
        wanted = false
        busy = true
        Thread({
            try {
                install()
            } catch (e: Exception) {
                status = "failed: ${e.message ?: e.javaClass.simpleName}"
                AppLog.w(TAG, "Update not installed: ${e.message}")
            } finally {
                busy = false
            }
        }, "self-update").start()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun install() {
        status = "checking"
        val release = UpdateCheck.latest() ?: throw IllegalStateException("can't reach GitHub")
        if (!UpdateCheck.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            status = "up to date"
            return
        }
        val apkUrl = release.apkUrl ?: throw IllegalStateException("the release has no APK")
        val shaUrl = release.shaUrl ?: throw IllegalStateException("the release has no checksum")

        status = "downloading ${release.version}"
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "openpane.apk")
        download(apkUrl, apk, MAX_APK_BYTES)
        val expected = String(fetch(shaUrl, 4096)).trim().substringBefore(' ').lowercase()
        val actual = sha256(apk)
        if (expected.length != 64 || expected != actual) throw IllegalStateException("the download doesn't match the release's checksum")

        val archive = archiveInfo(apk) ?: throw IllegalStateException("not an app package")
        val installed = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        if (archive.packageName != context.packageName) throw IllegalStateException("a different app")
        if (versionCode(archive) <= versionCode(installed)) throw IllegalStateException("not newer than what's installed")
        val archiveSigners = signers(archive)
        // Before Android 9 an APK file's signatures can't be read here; Android's own installer
        // still refuses an update signed with a different key, so that check stands in.
        val unreadable = Build.VERSION.SDK_INT < Build.VERSION_CODES.P && archiveSigners.isEmpty()
        if (!unreadable && (archiveSigners != signers(installed) || signers(installed).isEmpty())) {
            throw IllegalStateException("not signed with the same key as this app")
        }

        status = "asking for confirmation on the screen"
        AppLog.i(TAG, "Installing ${release.version}")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("openpane.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(context, InstallResultReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    private fun download(url: String, file: File, limit: Long) {
        open(url).let { c ->
            try {
                if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > limit) throw IllegalStateException("the download is too big")
                            out.write(buffer, 0, n)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private fun fetch(url: String, limit: Int): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            return c.inputStream.use { it.readNBytesCompat(limit) }
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun open(url: String): HttpURLConnection {
        // Only GitHub over https: the release assets live there.
        require(url.startsWith("https://github.com/") || url.startsWith("https://objects.githubusercontent.com/")) { "unexpected download address" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun archiveInfo(apk: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(apk.path, signingFlags())

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** SHA-256 of each signing certificate. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else info.signatures
        return certs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    companion object {
        private const val TAG = "SelfUpdate"
        private const val MAX_APK_BYTES = 100L * 1024 * 1024

        /** What the last install request is doing, for the state. */
        @Volatile var status: String = "idle"
    }
}

/** Android's answer to an install: show its confirmation, or record how it went. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    SelfUpdate.status = "failed: can't show Android's install confirmation"
                }
            }
            PackageInstaller.STATUS_SUCCESS -> SelfUpdate.status = "installed"
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $code"
                SelfUpdate.status = "failed: $message"
                AppLog.w("SelfUpdate", "Install failed: $message")
            }
        }
    }
}
