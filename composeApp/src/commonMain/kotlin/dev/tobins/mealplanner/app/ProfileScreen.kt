package dev.tobins.mealplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.targets.TargetCalculator
import kotlinx.coroutines.launch

private fun ActivityLevel.msg() = when (this) {
    ActivityLevel.SEDENTARY -> Msg.ACTIVITY_SEDENTARY
    ActivityLevel.LIGHT -> Msg.ACTIVITY_LIGHT
    ActivityLevel.MODERATE -> Msg.ACTIVITY_MODERATE
    ActivityLevel.ACTIVE -> Msg.ACTIVITY_ACTIVE
}

/** Body data and goal; the daily calorie and protein targets follow from them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(api: ApiClient, onBack: () -> Unit) {
    val t = LocalStrings.current
    var loaded by remember { mutableStateOf<Load<ProfileState>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var weight by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var activity by remember { mutableStateOf(ActivityLevel.LIGHT) }
    var goal by remember { mutableStateOf("0.5") }
    var kcalOverride by remember { mutableStateOf("") }
    var proteinOverride by remember { mutableStateOf("") }
    var likes by remember { mutableStateOf("") }
    var dislikes by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        loaded = Load.Loading
        loaded = load { api.profile() }
        (loaded as? Load.Ready)?.value?.profile?.let { profile ->
            weight = formatAmount(profile.weightKg, t.locale)
            height = formatAmount(profile.heightCm, t.locale)
            age = profile.ageYears.toString()
            sex = profile.sex
            activity = profile.activity
            goal = formatAmount(profile.goalKgPerWeek, t.locale)
            kcalOverride = profile.kcalOverride?.toString().orEmpty()
            proteinOverride = profile.proteinOverrideG?.toString().orEmpty()
            likes = profile.likes.joinToString(", ")
            dislikes = profile.dislikes.joinToString(", ")
        }
    }

    fun number(text: String) = text.trim().replace(',', '.').toDoubleOrNull()
    val overridesValid = (kcalOverride.isBlank() || kcalOverride.trim().toIntOrNull() != null) &&
        (proteinOverride.isBlank() || proteinOverride.trim().toIntOrNull() != null)
    val draft = run {
        val w = number(weight) ?: return@run null
        val h = number(height) ?: return@run null
        val a = age.trim().toIntOrNull() ?: return@run null
        val g = number(goal) ?: return@run null
        if (!overridesValid) return@run null
        fun words(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        Profile(w, h, a, sex, activity, g, kcalOverride.trim().toIntOrNull(), proteinOverride.trim().toIntOrNull(), words(likes), words(dislikes))
    }
    val problems = draft?.let(TargetCalculator::problems)

    SubScreen(t(Msg.PROFILE_AND_TARGETS), onBack, snackbar) { padding ->
        when (val current = loaded) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(Msg.PROFILE_LOAD_FAILED), current.message) { reload++ }
            }
            is Load.Ready -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
                verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
            ) {
                NumberField(t(Msg.WEIGHT_KG), weight, decimal = true) { weight = it }
                NumberField(t(Msg.HEIGHT_CM), height, decimal = true) { height = it }
                NumberField(t(Msg.AGE), age, decimal = false) { age = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    FilterChip(selected = sex == Sex.MALE, onClick = { sex = Sex.MALE }, label = { Text(t(Msg.MALE)) })
                    FilterChip(selected = sex == Sex.FEMALE, onClick = { sex = Sex.FEMALE }, label = { Text(t(Msg.FEMALE)) })
                }
                Text(t(Msg.ACTIVITY), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    ActivityLevel.entries.forEach { level ->
                        FilterChip(selected = activity == level, onClick = { activity = level }, label = { Text(t(level.msg())) })
                    }
                }
                NumberField(t(Msg.GOAL_PER_WEEK), goal, decimal = true) { goal = it }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                        Text(t(Msg.DAILY_TARGETS), style = MaterialTheme.typography.labelLarge)
                        when {
                            draft == null -> Text(t(Msg.FILL_IN))
                            !problems.isNullOrEmpty() -> Text(problems.joinToString("\n") { t.profileProblem(it) }, color = MaterialTheme.colorScheme.error)
                            else -> {
                                val targets = TargetCalculator.targets(draft)
                                Text(t(Msg.KCAL_PROTEIN, targets.kcal, targets.proteinG), style = MaterialTheme.typography.titleLarge.numeric())
                                Text(
                                    TargetCalculator.suggest(draft).let { t(Msg.MAINTENANCE, TargetCalculator.maintenanceKcal(draft), it.kcal, it.proteinG) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(t(Msg.OWN_TARGETS), style = MaterialTheme.typography.labelLarge)
                NumberField(t(Msg.CALORIES_PER_DAY), kcalOverride, decimal = false) { kcalOverride = it }
                NumberField(t(Msg.PROTEIN_PER_DAY), proteinOverride, decimal = false) { proteinOverride = it }

                Text(t(Msg.TASTE), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    likes, { likes = it },
                    label = { Text(t(Msg.LIKES)) },
                    supportingText = { Text(t(Msg.LIKES_HINT)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    dislikes, { dislikes = it },
                    label = { Text(t(Msg.DISLIKES)) },
                    supportingText = { Text(t(Msg.DISLIKES_HINT)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    enabled = draft != null && problems.isNullOrEmpty(),
                    onClick = {
                        scope.launch {
                            when (val result = load { api.saveProfile(draft!!) }) {
                                is Load.Ready -> {
                                    loaded = result
                                    snackbar.showSnackbar(t(Msg.PROFILE_SAVED))
                                }
                                is Load.Failed -> snackbar.showSnackbar(t(Msg.SAVE_FAILED, result.message))
                                Load.Loading -> Unit
                            }
                        }
                    },
                ) { Text(t(Msg.SAVE)) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, decimal: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
