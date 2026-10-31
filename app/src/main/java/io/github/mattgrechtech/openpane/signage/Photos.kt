package io.github.mattgrechtech.openpane.signage

import io.github.mattgrechtech.openpane.web.MiniWebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Where the photo frame's pictures come from ([io.github.mattgrechtech.openpane.settings.Keys.PHOTO_SOURCE]),
 * one per line: a folder in Home Assistant's media (`/media/local/photos`, or a `media-source://`
 * address from the media browser, which also reaches photo integrations), or a single picture
 * (`/local/frame/1.jpg`, or an http(s) URL).
 */
object Photos {

    /** A picture to keep: [key] stays the same for the same picture; [path] is what to download. */
    data class Picture(val key: String, val path: String, val needsResolve: Boolean = false)

    const val MAX_PICTURES = 500
    private const val MAX_DEPTH = 2

    /** The folders ([media-source://] addresses) and single pictures in [text]. */
    fun parse(text: String): Pair<List<String>, List<Picture>> {
        val folders = mutableListOf<String>()
        val pictures = mutableListOf<Picture>()
        for (raw in text.lines()) {
            val line = raw.trim().trimEnd('/')
            when {
                line.isEmpty() -> Unit
                line.startsWith("media-source://") -> folders += line
                Playlist.kindOf(line) == Playlist.Kind.IMAGE && (line.startsWith("/") || line.startsWith("http://") || line.startsWith("https://")) ->
                    pictures += Picture(line, line)
                line.startsWith("/media/") -> folders += "media-source://media_source/" + line.removePrefix("/media/")
            }
        }
        return folders to pictures
    }

    /** What's wrong with [text], or null. */
    fun problem(text: String): String? {
        for ((index, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val (folders, pictures) = parse(line)
            if (folders.isEmpty() && pictures.isEmpty()) {
                return "line ${index + 1}: use a media folder like /media/local/photos, a media-source:// address, or a picture's address"
            }
        }
        return null
    }

    /**
     * A local media file's download path: `media-source://media_source/local/a/b.jpg` is served
     * at `/media/local/a/b.jpg`. Null for other sources (photo integrations), which need resolving.
     */
    fun localPath(id: String): String? =
        id.takeIf { it.startsWith("media-source://media_source/") }
            ?.let { "/media/" + it.removePrefix("media-source://media_source/").split('/').joinToString("/") { part -> encode(part) } }

    private fun encode(part: String) = java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")

    /** The pictures and sub-folders in a `media_source/browse_media` result. */
    fun children(result: JSONObject): Pair<List<String>, List<String>> {
        val folders = mutableListOf<String>()
        val images = mutableListOf<String>()
        val list = result.optJSONArray("children") ?: JSONArray()
        for (i in 0 until list.length()) {
            val child = list.optJSONObject(i) ?: continue
            val id = child.optString("media_content_id")
            if (id.isEmpty()) continue
            when {
                child.optBoolean("can_expand") -> folders += id
                child.optString("media_class") == "image" || child.optString("media_content_type").startsWith("image/") -> images += id
            }
        }
        return folders to images
    }

    /**
     * Lists the pictures in [folders] (and their sub-folders, two levels down) over Home Assistant's
     * WebSocket API. Blocking. Throws if Home Assistant can't be reached; an unknown folder is skipped.
     * [have]: whether a picture is already on the device.
     */
    @Throws(IOException::class)
    fun browse(base: String, token: String, folders: List<String>, have: (String) -> Boolean): List<Picture> {
        if (folders.isEmpty()) return emptyList()
        val pictures = mutableListOf<Picture>()
        MiniWebSocket.connect("$base/api/websocket").use { ws ->
            ws.setReadTimeout(20_000)
            if (JSONObject(ws.receiveText()).optString("type") != "auth_required") throw IOException("unexpected answer")
            ws.sendText(JSONObject().put("type", "auth").put("access_token", token).toString())
            if (JSONObject(ws.receiveText()).optString("type") != "auth_ok") throw IOException("Home Assistant refused the sign-in")
            var id = 0
            fun call(type: String, contentId: String): JSONObject? {
                val mine = ++id
                ws.sendText(JSONObject().put("id", mine).put("type", type).put("media_content_id", contentId).toString())
                while (true) {
                    val message = JSONObject(ws.receiveText())
                    if (message.optInt("id") != mine || message.optString("type") != "result") continue
                    return if (message.optBoolean("success")) message.optJSONObject("result") else null
                }
            }
            val queue = ArrayDeque(folders.map { it to 0 })
            val seen = mutableSetOf<String>()
            while (queue.isNotEmpty() && pictures.size < MAX_PICTURES) {
                val (folder, depth) = queue.removeFirst()
                if (!seen.add(folder)) continue
                val result = call("media_source/browse_media", folder) ?: continue
                val (subfolders, images) = children(result)
                for (image in images) {
                    if (pictures.size >= MAX_PICTURES) break
                    val local = localPath(image)
                    pictures += if (local != null) Picture(image, local) else Picture(image, image, needsResolve = true)
                }
                if (depth < MAX_DEPTH) subfolders.forEach { queue.addLast(it to depth + 1) }
            }
            // Photo integrations (not local files) give a signed address for each picture: asked
            // only for pictures not on the device yet. The others stay, still needing it.
            return pictures.map { picture ->
                if (!picture.needsResolve || have(picture.key)) picture
                else call("media_source/resolve_media", picture.key)?.optString("url")?.takeIf { it.isNotEmpty() }
                    ?.let { picture.copy(path = it, needsResolve = false) } ?: picture
            }
        }
    }
}
