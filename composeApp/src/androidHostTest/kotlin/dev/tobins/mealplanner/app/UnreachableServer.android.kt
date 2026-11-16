package dev.tobins.mealplanner.app

/** As OkHttp fails when nothing listens at the server's address. */
internal actual fun unreachableServer(): Throwable = java.net.ConnectException("Failed to connect to /10.0.2.2:8080")
