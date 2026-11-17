package dev.tobins.mealplanner.app

import io.ktor.client.engine.js.JsError

/** As Ktor's browser engine fails when the browser rejects the fetch (`commonFetch` in ktor-client-core, wasmJs). */
internal actual fun unreachableServer(): Throwable = Error("Fail to fetch", JsError("TypeError: Failed to fetch".toJsString()))
