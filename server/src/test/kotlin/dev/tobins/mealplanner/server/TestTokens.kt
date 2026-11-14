package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.AuthConfig

/**
 * Stands in for Authelia in tests: any token is valid and names its account ("t" is the
 * account t, "bob" the account bob), except "nope" and blank ones.
 */
object TestTokens : TokenVerifier {
    override suspend fun verify(token: String): Identity? =
        token.takeIf { it.isNotBlank() && it != "nope" }?.let { Identity(subject = "sub-$it", username = it, displayName = it.replaceFirstChar(Char::uppercase)) }

    override suspend fun clientConfig() = AuthConfig(
        issuer = "https://auth.example.org",
        clientId = "mealplanner",
        authorizationEndpoint = "https://auth.example.org/api/oidc/authorization",
        tokenEndpoint = "https://auth.example.org/api/oidc/token",
        scopes = LOGIN_SCOPES,
    )
}
