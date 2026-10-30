package io.github.mattgrechtech.tvkiosk.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.mattgrechtech.tvkiosk.diag.AppLog
import io.github.mattgrechtech.tvkiosk.ui.Dialogs
import io.github.mattgrechtech.tvkiosk.ui.TvUi

/**
 * Shows [Popup]s in the screen corners. With "display over other apps" they are drawn in an
 * overlay window above any app; without it, only inside the dashboard while it is open.
 * Up to [maxPerCorner] stack in each corner; a pop-up with the id of one on screen replaces it.
 *
 * Pop-ups never take the remote, except ones with buttons: while the dashboard is in front, or
 * over other apps when [takeRemote] allows it. Then OK presses a button and Back closes the
 * pop-up, and the remote goes back to whatever was in front as soon as it closes.
 * Main thread only.
 */
class PopupManager(private val context: Context) {

    /** How many may stack in one corner (the pop-up settings). */
    var maxPerCorner: () -> Int = { 3 }
    /** Whether pop-ups with buttons may take the remote from other apps. */
    var takeRemote: () -> Boolean = { false }
    /** Runs a pop-up button's action; the pop-up closes afterwards. */
    var onAction: (Popup, Popup.Action) -> Unit = { _, _ -> }
    /** A pop-up left the screen (closed, timed out, replaced or cleared). */
    var onRemoved: (Popup) -> Unit = {}

    /** Set by the dashboard: while it's in front, pop-ups with buttons always take the remote. */
    var dashboardInFront = false
        set(value) {
            field = value
            updateFocus()
        }

    enum class Shown { OVER_EVERYTHING, IN_DASHBOARD, NOWHERE }

    private class Corner(val container: LinearLayout, val inWindow: Boolean, val params: WindowManager.LayoutParams?) {
        var focusable = false
    }

    private class Entry(val popup: Popup, val view: View, val corner: Popup.Corner, val dismiss: Runnable) {
        var stream: LiveStream? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val corners = mutableMapOf<Popup.Corner, Corner>()
    private val entries = linkedMapOf<String, Entry>()
    private var dashboardFrame: FrameLayout? = null

    /** The dashboard's root view, used when the overlay permission is missing. */
    fun attachDashboard(frame: FrameLayout) {
        dashboardFrame = frame
    }

    fun detachDashboard(frame: FrameLayout) {
        if (dashboardFrame !== frame) return
        // Pop-ups living inside the dashboard go with it.
        corners.filterValues { !it.inWindow }.keys.toList().forEach { removeCorner(it) }
        dashboardFrame = null
    }

    /** True while any pop-up is on screen. */
    val showing: Boolean get() = entries.isNotEmpty()

    fun show(popup: Popup): Shown {
        val overWindows = Settings.canDrawOverlays(context)
        if (!overWindows && dashboardFrame == null) return Shown.NOWHERE

        val previous = entries.remove(popup.id)
        val previousIndex = previous?.let { old -> corners[old.corner]?.container?.indexOfChild(old.view) } ?: -1
        val replacing = previous != null && previous.corner == popup.corner
        previous?.let { remove(it) }
        val corner = cornerFor(popup.corner, overWindows) ?: return Shown.NOWHERE

        while (corner.container.childCount >= maxPerCorner().coerceIn(1, 3)) {
            val oldest = entries.values.firstOrNull { it.corner == popup.corner } ?: break
            entries.remove(oldest.popup.id)
            remove(oldest)
        }

        val view = buildView(popup)
        val atBottom = popup.corner == Popup.Corner.BOTTOM_START || popup.corner == Popup.Corner.BOTTOM_END
        // Newest nearest the screen edge.
        val index = if (replacing && previousIndex in 0..corner.container.childCount) previousIndex
        else if (atBottom) corner.container.childCount else 0
        corner.container.addView(view, index)
        if (!replacing) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(200).start()
        }

        val dismiss = Runnable { entries[popup.id]?.let { if (it.view === view) clear(popup.id) } }
        entries[popup.id] = Entry(popup, view, popup.corner, dismiss)
        // 0 means until it's closed; even then, never forever.
        val seconds = if (popup.durationSeconds > 0) popup.durationSeconds else Popup.MAX_STICKY
        main.postDelayed(dismiss, seconds * 1000L)
        updateFocus()
        return if (corner.inWindow) Shown.OVER_EVERYTHING else Shown.IN_DASHBOARD
    }

    /** Removes one pop-up by id, or all of them when [id] is null. */
    fun clear(id: String?) {
        if (id == null) {
            entries.values.toList().forEach { remove(it) }
            entries.clear()
        } else {
            entries.remove(id)?.let { remove(it) }
        }
        updateFocus()
    }

    /** Closes the newest pop-up (Back on the dashboard). False when none is showing. */
    fun closeNewest(): Boolean {
        val newest = entries.keys.lastOrNull() ?: return false
        clear(newest)
        return true
    }

    /**
     * Makes each corner window focusable while it holds a pop-up with buttons that may take the
     * remote, and gives the newest such pop-up's first button the focus.
     */
    private fun updateFocus() {
        val mayTake = dashboardInFront || takeRemote()
        for ((which, corner) in corners) {
            val params = corner.params ?: continue
            val newest = entries.values.lastOrNull { it.corner == which && it.popup.actions.isNotEmpty() }
            val want = mayTake && newest != null
            if (want != corner.focusable) {
                corner.focusable = want
                params.flags = if (want) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                try {
                    windowManager.updateViewLayout(corner.container, params)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Can't change pop-up focus", e)
                }
            }
            // Buttons only look selectable when they really have the remote; otherwise Android
            // would still draw the first one as focused inside its own window.
            entries.values.filter { it.corner == which }.forEach { setButtonsFocusable(it.view, want && it === newest) }
            if (want) newest?.view?.let { view -> main.post { firstButton(view)?.requestFocus() } }
        }
    }

    private fun firstButton(view: View): View? = view.findViewWithTag(FIRST_BUTTON)

    private fun setButtonsFocusable(view: View, focusable: Boolean) {
        val row = view.findViewWithTag<ViewGroup>(BUTTON_ROW) ?: return
        for (i in 0 until row.childCount) row.getChildAt(i).isFocusable = focusable
        if (!focusable) row.findFocus()?.clearFocus()
    }

    /** Plays [url] (MJPEG) in pop-up [id]'s picture area until the pop-up goes. */
    fun playStream(id: String, url: String) {
        val entry = entries[id] ?: return
        val image = entry.view.findViewWithTag<ImageView>(PICTURE) ?: return
        entry.stream?.stop()
        entry.stream = LiveStream(url, maxSidePx = 1280) { frame ->
            main.post {
                if (entries[id] === entry && image.isAttachedToWindow) {
                    image.setImageBitmap(frame)
                    image.visibility = View.VISIBLE
                }
            }
        }.also { it.start() }
    }

    private fun remove(entry: Entry) {
        onRemoved(entry.popup)
        entry.stream?.stop()
        main.removeCallbacks(entry.dismiss)
        val corner = corners[entry.corner] ?: return
        corner.container.removeView(entry.view)
        if (corner.container.childCount == 0) removeCorner(entry.corner)
    }

    private fun cornerFor(which: Popup.Corner, inWindow: Boolean): Corner? {
        corners[which]?.let { existing ->
            if (existing.inWindow == inWindow) return existing
            // The permission changed since this corner was created: start it again.
            entries.values.filter { it.corner == which }.forEach { entries.remove(it.popup.id); main.removeCallbacks(it.dismiss) }
            removeCorner(which)
        }

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val gravity = gravityFor(which)
        val margin = dp(32)
        try {
            if (inWindow) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    OVERLAY_TYPE,
                    // Not FLAG_NOT_TOUCHABLE: Android 12+ forces such windows to 80 % opacity. The
                    // window is only as big as its cards, and touches outside it pass through.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    this.gravity = gravity
                    x = margin
                    y = margin
                }
                windowManager.addView(container, params)
                return Corner(container, true, params).also { corners[which] = it }
            } else {
                val frame = dashboardFrame ?: return null
                frame.addView(container, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity,
                ).apply { setMargins(margin, margin, margin, margin) })
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Can't add the pop-up container", e)
            return null
        }
        return Corner(container, false, null).also { corners[which] = it }
    }

    private fun removeCorner(which: Popup.Corner) {
        val corner = corners.remove(which) ?: return
        try {
            if (corner.inWindow) windowManager.removeView(corner.container)
            else (corner.container.parent as? ViewGroup)?.removeView(corner.container)
        } catch (e: Exception) {
            AppLog.w(TAG, "Removing the pop-up container failed", e)
        }
    }

    private fun buildView(popup: Popup): View {
        val accent = popup.color ?: DEFAULT_ACCENT
        val size = popup.size
        val textScale = size.scale * if (popup.largeText) LARGE_TEXT else 1f
        val width = dp(size.widthDp)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                // Only the card fades; images and text stay solid.
                setColor((Math.round(popup.opacity * 2.55f) shl 24) or CARD_RGB)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), accent)
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
        }

        if (popup.hasPicture) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = if (popup.image == null) View.VISIBLE else View.GONE
                setBackgroundColor(0xFF000000.toInt())
                tag = PICTURE
            }
            card.addView(image, LinearLayout.LayoutParams(width, dp(size.imageHeightDp)))
            if (popup.image != null) ImageLoader.load(popup.image, maxSidePx = 1280) { bitmap ->
                main.post {
                    if (bitmap != null && image.isAttachedToWindow) {
                        image.setImageBitmap(bitmap)
                        image.visibility = View.VISIBLE
                    }
                }
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(18), dp(14))
        }
        val iconSize = Math.round(40 * textScale)
        iconView(popup, accent, 26f * textScale)?.let {
            row.addView(it, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { marginEnd = dp(14) })
        }

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (popup.title.isNotEmpty()) texts.addView(text(popup.title, 21f * textScale, Color.WHITE, bold = true, maxLines = 2))
        if (popup.message.isNotEmpty()) texts.addView(text(popup.message, 17f * textScale, 0xFFE0E0E0.toInt(), bold = false, maxLines = 5))
        if (popup.source.isNotEmpty()) texts.addView(text(popup.source, 13f * textScale, 0xFF9E9E9E.toInt(), bold = false, maxLines = 1))
        popup.countdownSeconds?.let { seconds -> texts.addView(countdown(seconds, 30f * textScale, accent)) }
        popup.progress?.let { percent -> texts.addView(progressBar(percent, accent), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) }) }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        popup.qr?.let { content ->
            runCatching { Dialogs.qrBitmap(content, dp(QR_DP)) }.getOrNull()?.let { bitmap ->
                row.addView(ImageView(context).apply { setImageBitmap(bitmap) }, LinearLayout.LayoutParams(dp(QR_DP), dp(QR_DP)).apply { marginStart = dp(12) })
            }
        }

        card.addView(row, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (popup.actions.isNotEmpty()) card.addView(buttons(popup, textScale), LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Spacing between stacked cards.
        return FrameLayout(context).apply {
            setPadding(0, dp(6), 0, dp(6))
            addView(card)
        }
    }

    /**
     * The pop-up's buttons, then "Close". Back on any of them closes the pop-up; Left and Right
     * stay inside the row so the remote can't wander into the app underneath.
     */
    private fun buttons(popup: Popup, textScale: Float): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(14))
            tag = BUTTON_ROW
        }
        val all = popup.actions.map { it.label to { onAction(popup, it) } } + (CLOSE_LABEL to {})
        val views = all.mapIndexed { index, (label, run) ->
            TvUi.button(context, label, primary = index == 0) {
                run()
                clear(popup.id)
            }.apply {
                id = View.generateViewId()
                textSize = 16f * textScale
                minHeight = dp(44)
                minimumHeight = dp(44)
                setPadding(dp(18), dp(6), dp(18), dp(6))
                if (index == 0) tag = FIRST_BUTTON
                // Until updateFocus says this pop-up has the remote.
                isFocusable = false
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (event.action == KeyEvent.ACTION_UP) clear(popup.id)
                        true
                    } else false
                }
            }
        }
        views.forEachIndexed { index, button ->
            button.nextFocusLeftId = views[maxOf(0, index - 1)].id
            button.nextFocusRightId = views[minOf(views.lastIndex, index + 1)].id
            button.nextFocusUpId = button.id
            button.nextFocusDownId = button.id
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(10)
            })
        }
        return row
    }

    /** m:ss, ticking down to 0:00 while the pop-up is up. */
    private fun countdown(seconds: Int, sizeSp: Float, colour: Int): TextView {
        val view = text(Popup.formatCountdown(seconds), sizeSp, colour, bold = true, maxLines = 1)
        val endsAt = SystemClock.elapsedRealtime() + seconds * 1000L
        val tick = object : Runnable {
            override fun run() {
                if (!view.isAttachedToWindow && view.tag == TICKING) return
                view.tag = TICKING
                val left = ((endsAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                view.text = Popup.formatCountdown(left)
                if (left > 0) main.postDelayed(this, 250)
            }
        }
        main.post(tick)
        return view
    }

    private fun progressBar(percent: Int, colour: Int): View = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = percent
        progressTintList = android.content.res.ColorStateList.valueOf(colour)
    }

    private fun iconView(popup: Popup, accent: Int, emojiSp: Float): View? {
        val icon = popup.icon ?: return null
        if (icon.startsWith("mdi:")) {
            val emoji = MdiIcons.emojiFor(icon) ?: return null
            return TextView(context).apply {
                text = emoji
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, emojiSp)
            }
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }
        ImageLoader.load(icon, maxSidePx = 128) { bitmap ->
            main.post {
                if (bitmap != null && image.isAttachedToWindow) {
                    image.setImageBitmap(bitmap)
                    if (popup.color != null) image.setColorFilter(accent)
                    image.visibility = View.VISIBLE
                }
            }
        }
        return image
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean, maxLines: Int) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun gravityFor(corner: Popup.Corner) = when (corner) {
        Popup.Corner.TOP_START -> Gravity.TOP or Gravity.START
        Popup.Corner.TOP_END -> Gravity.TOP or Gravity.END
        Popup.Corner.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
        Popup.Corner.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
    }

    private fun dp(value: Int) = Math.round(value * context.resources.displayMetrics.density)

    private companion object {
        const val TAG = "PopupManager"
        const val PICTURE = "picture"
        const val TICKING = "ticking"
        const val QR_DP = 120


        /** The window type for drawing over other apps; Android 7 only has the older one. */
        @Suppress("DEPRECATION")
        val OVERLAY_TYPE = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        const val CARD_RGB = 0x1E1F22
        const val LARGE_TEXT = 1.25f
        const val FIRST_BUTTON = "first-button"
        const val BUTTON_ROW = "button-row"
        const val CLOSE_LABEL = "Close"
        val DEFAULT_ACCENT = 0xFF03A9F4.toInt()
    }
}
