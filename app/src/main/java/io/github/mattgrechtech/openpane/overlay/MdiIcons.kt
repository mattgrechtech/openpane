package io.github.mattgrechtech.openpane.overlay

/**
 * Stand-ins for Material Design Icons names (`mdi:...`) used in pop-ups. Rendering real MDI glyphs
 * needs the icon font and its name table bundled; until then common names map to emoji, matched
 * by the words in the icon name, and anything unknown shows no icon.
 */
object MdiIcons {

    private val byWord: List<Pair<String, String>> = listOf(
        "doorbell" to "🔔",       // bell
        "bell" to "🔔",
        "cctv" to "📹",           // video camera
        "camera" to "📷",
        "video" to "📹",
        "motion" to "🚶",         // person walking
        "walk" to "🚶",
        "garage" to "🚗",         // car
        "car" to "🚗",
        "door" to "🚪",
        "fire" to "🔥",
        "smoke" to "🔥",
        "alert" to "⚠️",
        "alarm" to "🚨",
        "siren" to "🚨",
        "information" to "ℹ️",
        "trash" to "🗑️",
        "delete" to "🗑️",
        "recycle" to "♻️",
        "television" to "📺",
        "tv" to "📺",
        "home" to "🏠",
        "house" to "🏠",
        "lightning" to "⚡",
        "flash" to "⚡",
        "power" to "⚡",
        "water" to "💧",
        "fridge" to "❄️",
        "snowflake" to "❄️",
        "thermometer" to "🌡️",
        "weather" to "⛅",
        "package" to "📦",
        "mail" to "✉️",
        "email" to "✉️",
        "phone" to "📱",
        "music" to "🎵",
        "lock" to "🔒",
        "key" to "🔑",
        "lightbulb" to "💡",
        "washing" to "🧺",
        "check" to "✅",
        "calendar" to "📅",
        "clock" to "⏰",
    )

    /** "mdi:cctv" -> an emoji, or null if the name isn't an MDI name or isn't known. */
    fun emojiFor(icon: String): String? {
        if (!icon.startsWith("mdi:")) return null
        val words = icon.removePrefix("mdi:").lowercase().split('-')
        return byWord.firstOrNull { (word, _) -> word in words }?.second
    }
}
