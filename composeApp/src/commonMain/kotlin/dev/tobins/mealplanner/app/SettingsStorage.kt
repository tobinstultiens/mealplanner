package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.Store

/** Small key-value storage on the device, for settings such as the server address. */
interface SettingsStorage {
    fun get(key: String): String?

    fun set(key: String, value: String)

    /** Forgets [key], so stored lists that are no longer needed take no room. */
    fun remove(key: String)
}

/** Keeps everything in memory; for previews and tests. */
class MemorySettingsStorage : SettingsStorage {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

private const val URL_KEY = "server.url"

/**
 * The stored server settings, falling back to [defaults] for what was never saved. Written the way
 * the settings screen writes an address, so that saving the same server compares equal and keeps
 * the login and the client (a built-in address with a trailing `/` read as another server).
 */
fun SettingsStorage.serverSettings(defaults: ServerSettings) = ServerSettings(url = normalUrl(get(URL_KEY) ?: defaults.url))

fun SettingsStorage.saveServerSettings(settings: ServerSettings) {
    set(URL_KEY, normalUrl(settings.url))
}

private fun normalUrl(url: String) = url.trim().trimEnd('/')

/** Whether a server was chosen on this device (saved in the settings, or logged in to), rather than taken from the build. */
fun SettingsStorage.hasSavedServer(): Boolean = !get(URL_KEY).isNullOrBlank()

/** Whether this looks like a server's address at all; the login and the settings take nothing else. */
fun ServerSettings.isAddress(): Boolean = url.startsWith("http://") || url.startsWith("https://")

/** Light, dark, or whatever the device uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The browser's page (`index.html`) reads this key and its values `LIGHT` and `DARK` to colour itself before the
 * app has started; changing either needs its script changed too (SettingsStorageTest pins them).
 */
private const val THEME_KEY = "theme"

fun SettingsStorage.themeMode(): ThemeMode =
    get(THEME_KEY)?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.SYSTEM

fun SettingsStorage.saveThemeMode(mode: ThemeMode) = set(THEME_KEY, mode.name)

private const val LANGUAGE_KEY = "language"

/** The language chosen in Settings; the device's own until then. */
fun SettingsStorage.appLanguage(): AppLanguage =
    get(LANGUAGE_KEY)?.let { stored -> AppLanguage.entries.firstOrNull { it.name == stored } } ?: AppLanguage.SYSTEM

fun SettingsStorage.saveAppLanguage(language: AppLanguage) = set(LANGUAGE_KEY, language.name)

private const val SYSTEM_COLORS_KEY = "systemColors"

/** Whether the colours come from the wallpaper (where the device has them); off by default. */
fun SettingsStorage.systemColors(): Boolean = get(SYSTEM_COLORS_KEY) == "true"

fun SettingsStorage.saveSystemColors(on: Boolean) = set(SYSTEM_COLORS_KEY, on.toString())

private const val SHOPPING_MODE_KEY = "shoppingMode"

/** Whether the Shopping screen is in shopping mode (big rows, screen kept on); off by default. */
fun SettingsStorage.shoppingMode(): Boolean = get(SHOPPING_MODE_KEY) == "true"

fun SettingsStorage.saveShoppingMode(on: Boolean) = set(SHOPPING_MODE_KEY, on.toString())

/** How the shopping list is grouped: by shop section, or by the store to buy at. */
enum class ShoppingView { SECTION, STORE }

private const val SHOPPING_VIEW_KEY = "shoppingView"

fun SettingsStorage.shoppingView(): ShoppingView =
    get(SHOPPING_VIEW_KEY)?.let { stored -> ShoppingView.entries.firstOrNull { it.name == stored } } ?: ShoppingView.SECTION

fun SettingsStorage.saveShoppingView(view: ShoppingView) = set(SHOPPING_VIEW_KEY, view.name)

private const val SHOPPING_STORE_KEY = "shoppingStore"

/** The store chosen as "the shop I'm in" in the per-store view, or null for the overview. */
fun SettingsStorage.shoppingStore(): Store? = get(SHOPPING_STORE_KEY)?.let { stored -> Store.entries.firstOrNull { it.name == stored } }

fun SettingsStorage.saveShoppingStore(store: Store?) = set(SHOPPING_STORE_KEY, store?.name ?: "")

private const val SHOPS_KEY = "shops"

/**
 * The stores shopped at, as the server last confirmed them for the account ([AccountShops]); all of
 * them until known. Before the setting moved to the account this was the device's own choice.
 */
fun SettingsStorage.shops(): Set<Store> {
    val stored = get(SHOPS_KEY) ?: return Store.entries.toSet()
    return Store.entries.filter { it.name in stored.split(',') }.toSet().ifEmpty { Store.entries.toSet() }
}

fun SettingsStorage.saveShops(shops: Set<Store>) = set(SHOPS_KEY, shops.joinToString(",") { it.name })

/** Forgets the stores shown, for when another account may log in on this device; all stores until the server answers. */
fun SettingsStorage.clearShops() = set(SHOPS_KEY, "")

/**
 * Ends the session and forgets what belongs to the account, so the next login starts clean: the
 * stores shown, and the stored shopping lists with their unsent ticks, which must never reach the
 * next account's list.
 */
fun SettingsStorage.logOut() {
    saveSession(null)
    clearShops()
    ShoppingCache(this).clear()
}

private const val SHOPS_MOVED_KEY = "shopsMovedToAccount"

/** Whether this device's own choice of stores has been offered to the account, which happens once. */
fun SettingsStorage.shopsMoved(): Boolean = get(SHOPS_MOVED_KEY) == "true"

fun SettingsStorage.markShopsMoved() = set(SHOPS_MOVED_KEY, "true")

private const val PRODUCT_PICTURES_KEY = "productPictures"

/** Whether offers and shopping rows load the stores' product pictures; on by default. */
fun SettingsStorage.productPictures(): Boolean = get(PRODUCT_PICTURES_KEY) != "false"

fun SettingsStorage.saveProductPictures(on: Boolean) = set(PRODUCT_PICTURES_KEY, on.toString())
