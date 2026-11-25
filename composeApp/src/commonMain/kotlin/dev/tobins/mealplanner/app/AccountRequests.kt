package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.tobins.mealplanner.api.AccountRequest
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What the owner's Requests card needs of the server (issue 314): the requests for an account that wait, and approving or
 * rejecting one, each answering those still waiting. Only the account that decides on them ([dev.tobins.mealplanner.api.Me.approvesRequests])
 * gets one; the server refuses the routes to every other account as well.
 */
class AccountDecisions(
    val waiting: suspend () -> List<AccountRequest>,
    val approve: suspend (username: String) -> List<AccountRequest>,
    val reject: suspend (username: String) -> List<AccountRequest>,
)

/** The decisions of [api]'s account. */
fun AccountDecisions(api: ApiClient) = AccountDecisions(api::accountRequests, api::approveRequest, api::rejectRequest)

/**
 * Why a decision on a request failed, in [t]'s words, and whether the list is to be asked for again because the request
 * went meanwhile: 404, it waits no longer (decided on another device, or expired); 409, it was decided in the directory
 * by hand. The directory unreachable (503) leaves the request waiting, to try again.
 */
internal fun decisionFailure(failed: Load.Failed, t: Strings): Pair<String, Boolean> = when (failed.status) {
    404 -> t(Msg.REQUEST_GONE) to true
    409 -> t(Msg.REQUEST_DECIDED_ELSEWHERE) to true
    503 -> t(Msg.REQUEST_DIRECTORY_DOWN) to false
    else -> failureSentence(failed, Msg.DECIDE_FAILED) to false
}

/**
 * The owner's requests for an account, in Settings (issue 314): how many wait, and each with who asked, when, and when it
 * is rejected by itself, with Approve and Reject. Reject asks first, as it deletes the account the person made. Asked for
 * each time the card is shown, so that it is current when a notification opened it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountRequestsCard(decisions: AccountDecisions, snackbar: SnackbarHostState) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<Load<List<AccountRequest>>>(Load.Loading) }
    // The user name being decided on: its buttons wait meanwhile.
    var deciding by remember { mutableStateOf<String?>(null) }
    var rejecting by remember { mutableStateOf<AccountRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(decisions) { requests = load { decisions.waiting() } }

    fun decide(request: AccountRequest, change: suspend (String) -> List<AccountRequest>, done: Msg) {
        deciding = request.username
        error = null
        scope.launch {
            when (val result = load { change(request.username) }) {
                is Load.Ready -> {
                    requests = result
                    launch { snackbar.showSnackbar(t(done, request.displayName)) }
                }
                is Load.Failed -> {
                    val (why, gone) = decisionFailure(result, t)
                    error = why
                    if (gone) requests = load { decisions.waiting() }
                }
                Load.Loading -> Unit
            }
            deciding = null
        }
    }

    SettingsCard(t(Msg.CARD_REQUESTS)) {
        when (val shown = requests) {
            Load.Loading -> Text(t(Msg.LOADING), style = MaterialTheme.typography.bodyMedium)
            is Load.Failed -> {
                ErrorBanner(t(Msg.REQUESTS_LOAD_FAILED), failureSentence(shown))
                TextButton(onClick = { scope.launch { requests = Load.Loading; requests = load { decisions.waiting() } } }) { Text(t(Msg.TRY_AGAIN)) }
            }
            is Load.Ready -> {
                val list = shown.value
                Text(
                    if (list.isEmpty()) t(Msg.REQUESTS_NONE) else t.plural(Plural.REQUESTS_WAITING, list.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                list.forEach { request ->
                    HorizontalDivider()
                    RequestRow(
                        request,
                        enabled = deciding == null,
                        onApprove = { decide(request, decisions.approve, Msg.REQUEST_APPROVED) },
                        onReject = { rejecting = request },
                    )
                }
            }
        }
        error?.let { PlainError(it) }
    }
    rejecting?.let { request ->
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text(t(Msg.REJECT_QUESTION_TITLE, request.displayName)) },
            text = { Text(t(Msg.REJECT_QUESTION_TEXT, request.username)) },
            confirmButton = {
                TextButton(onClick = {
                    rejecting = null
                    decide(request, decisions.reject, Msg.REQUEST_REJECTED)
                }) { Text(t(Msg.REJECT)) }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text(t(Msg.CANCEL)) } },
        )
    }
}

/** One request: the name, the user name and address, when it was made and when it goes by itself, and the two choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestRow(request: AccountRequest, enabled: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    val t = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(request.displayName, style = MaterialTheme.typography.titleSmall)
        Text(unbrokenParts("${request.username} · ${request.email}"), style = MaterialTheme.typography.bodySmall)
        Text(
            t(Msg.REQUEST_DATES, instantLabel(request.requestedAt, t), dayLabel(localDate(request.expiresAt), t.locale)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
            Button(enabled = enabled, onClick = onApprove) { Text(t(Msg.APPROVE)) }
            OutlinedButton(enabled = enabled, onClick = onReject) { Text(t(Msg.REJECT)) }
        }
    }
}

/** The day of the ISO instant [iso] on this device's clock. */
@OptIn(ExperimentalTime::class)
private fun localDate(iso: String): String = Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
