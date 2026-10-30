package io.github.mattgrechtech.openpane.control

import org.json.JSONObject

/** Outcome of a command, shaped for both HTTP and MQTT. */
class CommandResult(val status: Int, val body: JSONObject) {
    val ok get() = status in 200..299

    companion object {
        fun ok(body: JSONObject = JSONObject()) = CommandResult(200, body.put("ok", true))
        fun error(status: Int, message: String) =
            CommandResult(status, JSONObject().put("ok", false).put("error", message))
    }
}
