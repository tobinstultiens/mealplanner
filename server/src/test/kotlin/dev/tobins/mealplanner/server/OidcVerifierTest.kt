package dev.tobins.mealplanner.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The real token check against a tiny OpenID Connect provider: a discovery document and a
 * JWKS served over HTTP, and tokens signed with its key, as Authelia issues them.
 */
class OidcVerifierTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val other: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val issuer: String

    init {
        issuer = "http://127.0.0.1:${server.address.port}"
        val publicKey = keys.public as RSAPublicKey
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.dropWhile { it == 0.toByte() }.toByteArray())
        val jwks = """{"keys":[{"kty":"RSA","kid":"main","use":"sig","alg":"RS256","n":"${b64(publicKey.modulus.toByteArray())}","e":"${b64(publicKey.publicExponent.toByteArray())}"}]}"""
        val discovery = """{"issuer":"$issuer","authorization_endpoint":"$issuer/api/oidc/authorization","token_endpoint":"$issuer/api/oidc/token","userinfo_endpoint":"$issuer/api/oidc/userinfo","jwks_uri":"$issuer/jwks.json"}"""
        fun serve(path: String, body: () -> String) = server.createContext(path) { exchange ->
            val bytes = body().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (path.endsWith("userinfo") && exchange.requestHeaders["Authorization"] == null) 401 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        serve("/.well-known/openid-configuration") { discovery }
        serve("/jwks.json") { jwks }
        serve("/api/oidc/userinfo") { """{"sub":"user-1","preferred_username":"tobins","name":"Tobin"}""" }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun token(
        signer: KeyPair = keys,
        iss: String = issuer,
        clientId: String = "mealplanner",
        expires: Instant = Instant.now().plusSeconds(300),
    ): String = JWT.create()
        .withKeyId("main")
        .withIssuer(iss)
        .withSubject("user-1")
        .withAudience(clientId)
        .withClaim("client_id", clientId)
        .withExpiresAt(expires)
        .sign(Algorithm.RSA256(signer.public as RSAPublicKey, signer.private as RSAPrivateKey))

    @Test
    fun acceptsOnlyValidTokensForThisApp() = runBlocking {
        val verifier = OidcVerifier(issuer, "mealplanner")

        assertEquals("user-1", verifier.verify(token())?.subject)
        assertNull(verifier.verify(token(signer = other)), "signed with another key")
        assertNull(verifier.verify(token(iss = "https://elsewhere.example.org")), "another issuer")
        assertNull(verifier.verify(token(clientId = "homeassistant")), "issued to another app")
        assertNull(verifier.verify(token(expires = Instant.now().minusSeconds(120))), "expired")
        assertNull(verifier.verify("not a token"))
    }

    @Test
    fun namesComeFromUserinfoAndClientsGetTheEndpoints() = runBlocking {
        val verifier = OidcVerifier(issuer, "mealplanner")
        assertEquals(Identity("user-1", "tobins", "Tobin"), verifier.userinfo(token()))

        val config = verifier.clientConfig()
        assertEquals("$issuer/api/oidc/authorization", config.authorizationEndpoint)
        assertEquals("$issuer/api/oidc/token", config.tokenEndpoint)
        assertEquals("mealplanner", config.clientId)
    }
}
