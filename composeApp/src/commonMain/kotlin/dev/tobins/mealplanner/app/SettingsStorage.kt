package dev.tobins.mealplanner.app

/** Small key-value storage on the device, for settings such as the server address. */
interface SettingsStorage {
    fun get(key: String): String?

    fun set(key: String, value: String)
}

/** Keeps everything in memory; for previews and tests. */
class MemorySettingsStorage : SettingsStorage {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }
}

private const val URL_KEY = "server.url"
private const val TOKEN_KEY = "server.token"

/** The stored server settings, falling back to [defaults] for what was never saved. */
fun SettingsStorage.serverSettings(defaults: ServerSettings) =
    ServerSettings(url = get(URL_KEY) ?: defaults.url, token = get(TOKEN_KEY) ?: defaults.token)

fun SettingsStorage.saveServerSettings(settings: ServerSettings) {
    set(URL_KEY, settings.url.trim().trimEnd('/'))
    set(TOKEN_KEY, settings.token.trim())
}
