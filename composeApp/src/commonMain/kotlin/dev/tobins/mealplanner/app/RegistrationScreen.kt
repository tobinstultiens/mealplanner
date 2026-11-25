package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.RegistrationReceipt
import dev.tobins.mealplanner.api.RegistrationRequest
import kotlinx.coroutines.launch

/**
 * Asking for an account (issue 314), opened from the login screen while the server takes requests: name, user name,
 * e-mail address and the password twice, checked as they are typed with the server's own rules ([registrationProblems]),
 * then sent with [send]. A field's problem shows once something typed in it is left, and every field's after Send; the
 * server's refusals are said in plain words ([registrationOutcome]). Once taken, the screen says that the request waits
 * for the owner's approval.
 *
 * The passwords are held with `remember`, never `rememberSaveable`, nor in storage: Android writes saved state into the
 * activity's bundle, which may be kept on disk while the app is in the background. So they live only as long as this
 * form is in the composition, and when Android re-creates the screen (a rotation, say) the two password fields are empty
 * again while the other fields stay. They are cleared as soon as the request was taken.
 */
@Composable
fun RegistrationScreen(send: suspend (RegistrationRequest) -> RegistrationReceipt, onBack: () -> Unit) {
    val t = LocalStrings.current
    var displayName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordAgain by remember { mutableStateOf("") }
    // Send was tried: every field says what is wrong with it from now on.
    var tried by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf<FormField?>(null) }
    var refusal by rememberSaveable { mutableStateOf<String?>(null) }
    // The field the server named in its refusal, marked until it is changed.
    var refusedField by rememberSaveable { mutableStateOf<FormField?>(null) }
    var sentAs by rememberSaveable { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    sentAs?.let { return RegistrationSent(it, onBack) }
    val draft = RegistrationDraft(displayName, username, email, password, passwordAgain)
    val problems = registrationProblems(draft, t)
    val shown = problems.filterKeys { field ->
        tried || field == refusedField || (field != focused && draft.value(field).isNotEmpty())
    } + listOfNotNull(refusedField?.takeIf { it !in problems }?.let { it to problemOf(it, t) })

    RegistrationForm(
        draft = draft,
        problems = shown,
        refusal = refusal,
        sending = sending,
        onChange = { changed ->
            FormField.entries.firstOrNull { draft.value(it) != changed.value(it) }?.let { if (it == refusedField) refusedField = null }
            displayName = changed.displayName
            username = changed.username
            email = changed.email
            password = changed.password
            passwordAgain = changed.passwordAgain
            refusal = null
        },
        onFocus = { field, has -> if (has) focused = field else if (focused == field) focused = null },
        onSend = {
            tried = true
            // Said under Send too: at a large font the marked field may be off the screen.
            refusal = t(Msg.REGISTER_FIX_FIELDS).takeIf { problems.isNotEmpty() }
            if (problems.isNotEmpty()) return@RegistrationForm
            sending = true
            scope.launch {
                when (val outcome = registrationOutcome(t) { send(draft.request()) }) {
                    is RegistrationOutcome.Sent -> {
                        password = ""
                        passwordAgain = ""
                        sentAs = outcome.username
                    }
                    is RegistrationOutcome.Refused -> {
                        refusal = outcome.message
                        refusedField = outcome.field
                    }
                }
                sending = false
            }
        },
        onBack = onBack,
    )
}

/** What [field] holds in this draft. */
private fun RegistrationDraft.value(field: FormField): String = when (field) {
    FormField.DISPLAY_NAME -> displayName
    FormField.USERNAME -> username
    FormField.EMAIL -> email
    FormField.PASSWORD -> password
    FormField.PASSWORD_AGAIN -> passwordAgain
}

/** [this] draft with [field] set to [value]. */
private fun RegistrationDraft.with(field: FormField, value: String): RegistrationDraft = when (field) {
    FormField.DISPLAY_NAME -> copy(displayName = value)
    FormField.USERNAME -> copy(username = value)
    FormField.EMAIL -> copy(email = value)
    FormField.PASSWORD -> copy(password = value)
    FormField.PASSWORD_AGAIN -> copy(passwordAgain = value)
}

/**
 * The form of [RegistrationScreen] as it is drawn: [draft] in its fields, [problems] under the fields they are about,
 * [refusal] (the server's answer, or what to fix) under Send.
 */
@Composable
fun RegistrationForm(
    draft: RegistrationDraft,
    problems: Map<FormField, String>,
    refusal: String?,
    sending: Boolean,
    onChange: (RegistrationDraft) -> Unit,
    onFocus: (FormField, Boolean) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    val t = LocalStrings.current
    SubScreen(t(Msg.REGISTER), onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
        ) {
            val narrow = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            Text(t(Msg.REGISTER_INTRO), narrow, style = MaterialTheme.typography.bodyMedium)
            for (field in FormField.entries) {
                val problem = problems[field]
                OutlinedTextField(
                    draft.value(field), { onChange(draft.with(field, it)) },
                    label = { Text(t(field.label)) },
                    supportingText = (problem ?: field.hint?.let(t::invoke))?.let { { Text(it) } },
                    isError = problem != null,
                    singleLine = true,
                    enabled = !sending,
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = field.keyboard,
                    modifier = narrow.onFocusChanged { onFocus(field, it.isFocused) },
                )
            }
            Button(enabled = !sending, onClick = onSend, modifier = narrow) {
                Text(if (sending) t(Msg.REGISTER_SENDING) else t(Msg.REGISTER_SEND))
            }
            refusal?.let { PlainError(it, narrow) }
        }
    }
}

private val FormField.label: Msg
    get() = when (this) {
        FormField.DISPLAY_NAME -> Msg.FIELD_DISPLAY_NAME
        FormField.USERNAME -> Msg.FIELD_USERNAME
        FormField.EMAIL -> Msg.FIELD_EMAIL
        FormField.PASSWORD -> Msg.FIELD_PASSWORD
        FormField.PASSWORD_AGAIN -> Msg.FIELD_PASSWORD_AGAIN
    }

/** What a field says under it while nothing is wrong with it. */
private val FormField.hint: Msg?
    get() = if (this == FormField.USERNAME) Msg.FIELD_USERNAME_HINT else null

private val FormField.secret: Boolean get() = this == FormField.PASSWORD || this == FormField.PASSWORD_AGAIN

/** The keyboard of each field: no corrections where they would change what is typed, and no learning of the passwords. */
private val FormField.keyboard: KeyboardOptions
    get() = when (this) {
        FormField.DISPLAY_NAME -> KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        FormField.USERNAME -> KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)
        FormField.EMAIL -> KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email)
        FormField.PASSWORD, FormField.PASSWORD_AGAIN -> KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    }

/**
 * A failure said in full, with the error icon: for a sentence the app wrote that the person must read to go on, unlike
 * [ErrorBanner], which keeps its details behind a button.
 */
@Composable
internal fun PlainError(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
        Icon(AppIcons.Error, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** The request for the account [username] was taken: it waits for the owner. */
@Composable
fun RegistrationSent(username: String, onBack: () -> Unit) {
    val t = LocalStrings.current
    SubScreen(t(Msg.REGISTER), onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
            verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
        ) {
            val narrow = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            Text(t(Msg.REGISTER_SENT_TITLE), narrow, style = MaterialTheme.typography.titleMedium)
            Text(t(Msg.REGISTER_SENT, username), narrow, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onBack, modifier = narrow) { Text(t(Msg.BACK_TO_LOGIN)) }
        }
    }
}
