package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.AuthConfig

/**
 * Stands in for Authelia in tests: any token is valid and names its account ("t" is the
 * account t, "bob" the account bob), except "nope" and blank ones. After a `~` come the groups
 * the token lists, each after a `~` of its own: "ann~mealplanner~family" is ann in two groups,
 * "ann~" ann in none; a token without a `~` has no groups claim at all, as one from before the
 * server asked for it. (A bearer token may hold letters, digits and `-._~+/` only.)
 */
object TestTokens : TokenVerifier {
    override suspend fun verify(token: String): Identity? {
        if (token.isBlank() || token == "nope") return null
        val name = token.substringBefore('~')
        val groups = if ('~' in token) token.substringAfter('~').split('~').filter { it.isNotEmpty() } else null
        return Identity(subject = "sub-$name", username = name, displayName = name.replaceFirstChar(Char::uppercase), groups = groups)
    }

    override suspend fun clientConfig() = AuthConfig(
        issuer = "https://auth.example.org",
        clientId = "mealplanner",
        authorizationEndpoint = "https://auth.example.org/api/oidc/authorization",
        tokenEndpoint = "https://auth.example.org/api/oidc/token",
        scopes = LOGIN_SCOPES,
    )
}
