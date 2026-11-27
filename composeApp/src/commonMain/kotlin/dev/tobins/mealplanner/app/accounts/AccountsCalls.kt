package dev.tobins.mealplanner.app.accounts

import dev.tobins.mealplanner.Health
import dev.tobins.mealplanner.api.AccountRequest
import dev.tobins.mealplanner.api.Me
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRequest
import dev.tobins.mealplanner.api.RegistrationStatus
import dev.tobins.mealplanner.api.ShopsSetting
import dev.tobins.mealplanner.app.ApiClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/*
 * The account's server calls: who is logged in, asking for an account, the owner's decisions on requests, the profile and the shops.
 * They are extensions of [ApiClient], which keeps the address, the login, the errors and the retries for all of them.
 */

suspend fun ApiClient.me(): Me = http.get("api/me").body()

/** Whether the server takes requests for an account (issue 314); asked without a login. A server from before it answers 404. */
suspend fun ApiClient.registrationStatus(): RegistrationStatus = http.get("api/registration").body()

/**
 * Asks for an account (issue 314), without a login. A refusal is thrown as any failed call is; [registrationOutcome]
 * says what it means.
 */
suspend fun ApiClient.register(request: RegistrationRequest): RegistrationReceipt = http.post("api/registration") {
    contentType(ContentType.Application.Json)
    setBody(request)
}.body()

/** The requests for an account that wait, oldest first: the owner's account only ([Me.approvesRequests]). */
suspend fun ApiClient.accountRequests(): List<AccountRequest> = http.get("api/registrations").body()

/** Approves [username]'s request, answering the requests still waiting. */
suspend fun ApiClient.approveRequest(username: String): List<AccountRequest> = http.post("api/registrations/${username.encodeURLPathPart()}/approve").body()

/** Rejects [username]'s request, which deletes the account made for it; answers the requests still waiting. */
suspend fun ApiClient.rejectRequest(username: String): List<AccountRequest> = http.post("api/registrations/${username.encodeURLPathPart()}/reject").body()

suspend fun ApiClient.health(): Health = http.get("health").body()

/** The stores the account shops at; [ShopsSetting.stores] is null when it never chose. */
suspend fun ApiClient.shops(): ShopsSetting = http.get("api/shops").body()

suspend fun ApiClient.saveShops(setting: ShopsSetting): ShopsSetting = http.put("api/shops") {
    contentType(ContentType.Application.Json)
    setBody(setting)
}.body()
