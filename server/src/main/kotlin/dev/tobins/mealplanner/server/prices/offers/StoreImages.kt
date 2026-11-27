package dev.tobins.mealplanner.server.prices.offers

import java.net.URI

/**
 * The places the stores serve their product pictures from. The apps load these URLs
 * themselves, so a URL read from a scraped page is only passed on when it points here: a
 * changed page cannot make the apps fetch an arbitrary address. See docs/data-sources.md,
 * "Product pictures".
 */
object StoreImages {
    /**
     * Host and path prefix of each store's pictures. AH keeps product pictures and the pictures of
     * bonus groups (several products in one) apart; Jumbo and Lidl serve theirs from their main sites.
     */
    private val places = listOf(
        "static.ah.nl" to "/dam/product/",
        "static.ah.nl" to "/mam/bonus/",
        "www.jumbo.com" to "/INTERSHOP/static/",
        "www.lidl.nl" to "/assets/",
    )

    /**
     * [url] when it is an https address on one of the stores' image hosts, else null. Plain
     * http is dropped too: Android refuses it and the web client would be blocked as mixed content.
     */
    fun accept(url: String?): String? {
        val uri = url?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return null
        val path = uri.path ?: return null
        // A "/../" could climb out of the image directory on the store's main site.
        if (path.split('/').any { it == ".." || it == "." }) return null
        return url.trim().takeIf { places.any { (host, prefix) -> uri.host.equals(host, ignoreCase = true) && path.startsWith(prefix) } }
    }
}
