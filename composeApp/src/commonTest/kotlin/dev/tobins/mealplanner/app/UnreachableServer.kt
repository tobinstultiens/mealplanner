package dev.tobins.mealplanner.app

/**
 * What this platform's HTTP engine throws when the server cannot be reached, for a mock engine to
 * throw: OkHttp a `ConnectException`, Ktor's browser engine a `kotlin.Error` around the fetch's
 * `TypeError`, which is no `Exception` (#200).
 */
internal expect fun unreachableServer(): Throwable
