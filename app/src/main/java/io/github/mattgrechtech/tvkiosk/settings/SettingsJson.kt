package io.github.mattgrechtech.tvkiosk.settings

import org.json.JSONArray
import org.json.JSONObject

/** Settings as JSON, for export, import, adb provisioning and the API. */
object SettingsJson {

    class ImportResult(val applied: List<String>, val rejected: Map<String, String>) {
        fun toJson(): JSONObject = JSONObject()
            .put("applied", JSONArray(applied))
            .put("rejected", JSONObject(rejected as Map<*, *>))
    }

    /** Secrets are left out unless [includeSecrets]; empty secrets and internal keys are always left out. */
    fun export(settings: Settings, includeSecrets: Boolean): JSONObject {
        val json = JSONObject()
        for (key in Keys.ALL) {
            // Internal state (device id, refresh token) stays on the TV, so an export imports cleanly anywhere.
            if (key in Keys.NOT_IMPORTABLE) continue
            if (key.secret && !includeSecrets) continue
            val value: Any = settings[key]
            if (key.secret && value == "") continue
            json.put(key.name, value)
        }
        return json
    }

    /**
     * Every setting that can be changed, for the web admin page: name, type (`boolean`, `integer`
     * or `text`), default, whether it's a secret (write-only) and, for fixed sets, the choices.
     */
    fun schema(): JSONArray {
        val array = JSONArray()
        for (key in Keys.ALL) {
            if (key in Keys.NOT_IMPORTABLE) continue
            val item = JSONObject()
                .put("name", key.name)
                .put("type", when (key.default) { is Boolean -> "boolean"; is Int -> "integer"; else -> "text" })
                .put("default", key.default)
                .put("secret", key.secret)
            key.choices?.let { item.put("choices", JSONArray(it())) }
            array.put(item)
        }
        return array
    }

    /**
     * Applies every known, valid key in [json] in one edit. Unknown keys, internal keys and
     * invalid values are reported in [ImportResult.rejected] and change nothing.
     */
    fun import(settings: Settings, json: JSONObject): ImportResult {
        val applied = mutableListOf<String>()
        val rejected = linkedMapOf<String, String>()
        val accepted = mutableListOf<Pair<Key<*>, Any>>()

        for (name in json.keys()) {
            val key = Keys.byName(name)
            if (key == null) {
                rejected[name] = "unknown setting"
                continue
            }
            if (key in Keys.NOT_IMPORTABLE) {
                rejected[name] = "can't be set"
                continue
            }
            val value = coerce(key, json.get(name))
            if (value == null) {
                rejected[name] = "wrong type, expected ${typeName(key)}"
                continue
            }
            val problem = checkValue(key, value)
            if (problem != null) {
                rejected[name] = problem
                continue
            }
            accepted += key to value
            applied += name
        }

        if (accepted.isNotEmpty()) {
            settings.edit {
                for ((key, value) in accepted) putUnchecked(this, key, value)
            }
        }
        return ImportResult(applied, rejected)
    }

    private fun coerce(key: Key<*>, raw: Any?): Any? = when {
        raw == null || raw == JSONObject.NULL -> null
        // A list (e.g. "pages") may come as a JSON array of strings.
        key.default is String -> raw as? String ?: (raw as? Number)?.toString()
            ?: (raw as? JSONArray)?.let { array -> (0 until array.length()).joinToString("\n") { array.optString(it) } }
        key.default is Int -> when (raw) {
            is Int -> raw
            is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else null
            is Number -> raw.toDouble().let { if (it % 1.0 == 0.0) it.toInt() else null }
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        key.default is Boolean -> when (raw) {
            is Boolean -> raw
            is String -> Settings.decode(key, raw)
            else -> null
        }
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkValue(key: Key<*>, value: Any): String? = (key as Key<Any>).check(value)

    @Suppress("UNCHECKED_CAST")
    private fun putUnchecked(editor: Settings.Editor, key: Key<*>, value: Any) = editor.put(key as Key<Any>, value)

    private fun typeName(key: Key<*>) = when (key.default) {
        is String -> "text"
        is Int -> "a whole number"
        is Boolean -> "true or false"
        else -> "?"
    }
}
