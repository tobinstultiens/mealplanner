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

/** The stored server settings, falling back to [defaults] for what was never saved. */
fun SettingsStorage.serverSettings(defaults: ServerSettings) = ServerSettings(url = get(URL_KEY) ?: defaults.url)

fun SettingsStorage.saveServerSettings(settings: ServerSettings) {
    set(URL_KEY, settings.url.trim().trimEnd('/'))
}

/** Light, dark, or whatever the device uses. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

private const val THEME_KEY = "theme"

fun SettingsStorage.themeMode(): ThemeMode =
    get(THEME_KEY)?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM

fun SettingsStorage.saveThemeMode(mode: ThemeMode) = set(THEME_KEY, mode.name)

private const val SYSTEM_COLORS_KEY = "systemColors"

/** Whether the colours come from the wallpaper (where the device has them); off by default. */
fun SettingsStorage.systemColors(): Boolean = get(SYSTEM_COLORS_KEY) == "true"

fun SettingsStorage.saveSystemColors(on: Boolean) = set(SYSTEM_COLORS_KEY, on.toString())

private const val SHOPPING_MODE_KEY = "shoppingMode"

/** Whether the Shopping screen is in shopping mode (big rows, screen kept on); off by default. */
fun SettingsStorage.shoppingMode(): Boolean = get(SHOPPING_MODE_KEY) == "true"

fun SettingsStorage.saveShoppingMode(on: Boolean) = set(SHOPPING_MODE_KEY, on.toString())
