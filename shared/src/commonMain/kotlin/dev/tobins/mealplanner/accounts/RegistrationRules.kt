package dev.tobins.mealplanner.accounts

import dev.tobins.mealplanner.api.RegistrationRequest

/** A field of the form that asks for an account, by its [key] in [RegistrationRequest] and in the server's 400 (`field`). */
enum class RegistrationField(val key: String) {
    USERNAME("username"),
    DISPLAY_NAME("displayName"),
    EMAIL("email"),
    PASSWORD("password"),
}

/**
 * What a request for an account must hold (issue 314): one set of rules for the app's form, as the person types, and for
 * the server, which checks again. The registration helper next to the directory checks the same once more
 * (`scripts/registration-helper.py`): change them there too, and add the case to `scripts/registration-rules-cases.json`,
 * which both sides' tests read. Lengths count UTF-16 units, as Kotlin's strings do; the helper counts the same way.
 */
object RegistrationRules {
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 32
    const val DISPLAY_NAME_MAX = 64
    const val EMAIL_MAX = 254
    const val PASSWORD_MIN = 10
    const val PASSWORD_MAX = 128

    /**
     * Lower-case letters and digits, and `.`, `-` or `_` after the first: what the directory (LLDAP) keeps as a user id
     * without changing it, and what a distinguished name (`uid=...`) holds without escaping.
     */
    private val USERNAME = Regex("[a-z0-9][a-z0-9._-]{${USERNAME_MIN - 1},${USERNAME_MAX - 1}}")

    /**
     * Something, an @, something with a dot: the directory needs an address, and the rest is the person's to get right.
     * Printable ASCII only (`!` to `~`, the @ once): spelled out rather than "not whitespace", as Kotlin's `\s` and
     * Python's are not the same set, and the helper must refuse exactly what this refuses
     * (`scripts/registration-rules-cases.json`). An address with other letters is refused; few have them.
     */
    private val EMAIL = Regex("[!-?A-~]+@[!-?A-~]+\\.[!-?A-~]+")

    /** [request] as it is checked and sent on: the user name trimmed and in lower case, the names trimmed; the password as typed. */
    fun normalised(request: RegistrationRequest): RegistrationRequest = request.copy(
        username = request.username.trim().lowercase(),
        displayName = request.displayName.trim(),
        email = request.email.trim(),
    )

    /** The fields of [request] that break a rule, in the form's order; empty when it may be sent. Checks it as [normalised] makes it. */
    fun problems(request: RegistrationRequest): List<RegistrationField> {
        val r = normalised(request)
        return buildList {
            if (!USERNAME.matches(r.username)) add(RegistrationField.USERNAME)
            if (r.displayName.isEmpty() || r.displayName.length > DISPLAY_NAME_MAX || r.displayName.any { it.isISOControl() }) add(RegistrationField.DISPLAY_NAME)
            if (r.email.length > EMAIL_MAX || !EMAIL.matches(r.email)) add(RegistrationField.EMAIL)
            if (r.password.length !in PASSWORD_MIN..PASSWORD_MAX || r.password.equals(r.username, ignoreCase = true)) add(RegistrationField.PASSWORD)
        }
    }
}
