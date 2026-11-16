package dev.tobins.mealplanner.server

/** The app's login return as an Android intent URL, the form Chrome opens apps from. */
internal fun appIntentUrl(query: String): String =
    "intent://login" + (if (query.isEmpty()) "" else "?$query") + "#Intent;scheme=dev.tobins.mealplanner;package=dev.tobins.mealplanner;end"

/** HTML that sends the browser on to the app with the login answer; with a link in case it doesn't go by itself. */
internal fun appReturnPage(query: String): String {
    val target = appIntentUrl(query)
    val href = target.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Meal planner</title>
        <style>body{font-family:sans-serif;margin:2rem;color-scheme:light dark}a{font-size:1.2rem}</style></head>
        <body><p>Logged in. Returning to the app…</p><p><a id="open" href="$href">Open the Meal planner app</a></p>
        <script>location.replace(document.getElementById("open").href)</script></body></html>
    """.trimIndent()
}

/** Digital Asset Links: lets Android open the app for the login redirect on this host. */
internal fun assetLinks(certificates: List<String>): String {
    if (certificates.isEmpty()) return "[]"
    val prints = certificates.joinToString(",") { "\"${it.uppercase()}\"" }
    return """[{"relation":["delegate_permission/common.handle_all_urls"],""" +
        """"target":{"namespace":"android_app","package_name":"dev.tobins.mealplanner","sha256_cert_fingerprints":[$prints]}}]"""
}
