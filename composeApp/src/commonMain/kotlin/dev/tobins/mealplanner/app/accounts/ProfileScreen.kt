package dev.tobins.mealplanner.app.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.api.ActivityLevel
import dev.tobins.mealplanner.api.PlanPriority
import dev.tobins.mealplanner.api.Profile
import dev.tobins.mealplanner.api.ProfileState
import dev.tobins.mealplanner.api.Sex
import dev.tobins.mealplanner.app.ApiClient
import dev.tobins.mealplanner.app.LocalStrings
import dev.tobins.mealplanner.app.ui.CommonMsg
import dev.tobins.mealplanner.app.ui.ErrorMessage
import dev.tobins.mealplanner.app.ui.FormatLocale
import dev.tobins.mealplanner.app.ui.Load
import dev.tobins.mealplanner.app.ui.LoadingState
import dev.tobins.mealplanner.app.ui.Spacing
import dev.tobins.mealplanner.app.ui.SubScreen
import dev.tobins.mealplanner.app.ui.UnsavedChangesGuard
import dev.tobins.mealplanner.app.ui.failureSentence
import dev.tobins.mealplanner.app.ui.formatAmount
import dev.tobins.mealplanner.app.ui.load
import dev.tobins.mealplanner.app.ui.macrosText
import dev.tobins.mealplanner.app.ui.numeric
import dev.tobins.mealplanner.planner.PortionRange
import dev.tobins.mealplanner.targets.TargetCalculator
import kotlinx.coroutines.launch

private fun ActivityLevel.msg() = when (this) {
    ActivityLevel.SEDENTARY -> AccountsMsg.ACTIVITY_SEDENTARY
    ActivityLevel.LIGHT -> AccountsMsg.ACTIVITY_LIGHT
    ActivityLevel.MODERATE -> AccountsMsg.ACTIVITY_MODERATE
    ActivityLevel.ACTIVE -> AccountsMsg.ACTIVITY_ACTIVE
}

private fun PlanPriority.msg() = when (this) {
    PlanPriority.BALANCED -> AccountsMsg.PRIORITY_BALANCED
    PlanPriority.PROTEIN -> AccountsMsg.PRIORITY_PROTEIN
    PlanPriority.FIBRE -> AccountsMsg.PRIORITY_FIBRE
    PlanPriority.PRICE -> AccountsMsg.PRIORITY_PRICE
}

/**
 * The fields of the profile form as typed, so that they can be compared with what was loaded
 * and kept over a re-created activity. The defaults are a blank form, which is never shown: the form
 * is filled when the profile has loaded ([of]).
 */
private data class ProfileForm(
    val weight: String = "",
    val height: String = "",
    val age: String = "",
    val sex: Sex = Sex.MALE,
    val activity: ActivityLevel = ActivityLevel.LIGHT,
    val goal: String = "",
    val kcalOverride: String = "",
    val proteinOverride: String = "",
    val likes: String = "",
    val dislikes: String = "",
    val minPortions: String = "",
    val maxPortions: String = "",
    val priority: PlanPriority = PlanPriority.BALANCED,
    val fibreOverride: String = "",
) {
    companion object {
        /**
         * The form showing [profile]; for none, the empty form of an account without a profile, which
         * starts from the defaults. Numbers are written as [locale] writes them: 0,75 in Dutch.
         */
        fun of(profile: Profile?, locale: FormatLocale): ProfileForm = profile?.let {
            ProfileForm(
                weight = formatAmount(it.weightKg, locale),
                height = formatAmount(it.heightCm, locale),
                age = it.ageYears.toString(),
                sex = it.sex,
                activity = it.activity,
                goal = formatAmount(it.goalKgPerWeek, locale),
                kcalOverride = it.kcalOverride?.toString().orEmpty(),
                proteinOverride = it.proteinOverrideG?.toString().orEmpty(),
                likes = it.likes.joinToString(", "),
                dislikes = it.dislikes.joinToString(", "),
                minPortions = formatAmount(it.minPortions, locale),
                maxPortions = formatAmount(it.maxPortions, locale),
                priority = it.priority,
                fibreOverride = it.fibreOverrideG?.toString().orEmpty(),
            )
        } ?: ProfileForm(
            goal = formatAmount(Profile.DEFAULT_GOAL_KG_PER_WEEK, locale),
            minPortions = formatAmount(PortionRange.DEFAULT_MIN, locale),
            maxPortions = formatAmount(PortionRange.DEFAULT_MAX, locale),
        )

        private fun ProfileForm.fields() = listOf(
            weight, height, age, sex.name, activity.name, goal, kcalOverride, proteinOverride, likes, dislikes, minPortions, maxPortions,
            priority.name, fibreOverride,
        )

        private fun fromFields(v: List<String>) = ProfileForm(
            v[0], v[1], v[2], Sex.valueOf(v[3]), ActivityLevel.valueOf(v[4]), v[5], v[6], v[7], v[8], v[9], v[10], v[11],
            PlanPriority.valueOf(v[12]), v[13],
        )

        val Saver = listSaver<ProfileForm, String>(save = { it.fields() }, restore = ::fromFields)

        /** For the form as loaded, which is not there before the load is done. */
        val OptionalSaver = listSaver<ProfileForm?, String>(
            save = { it?.fields().orEmpty() },
            restore = { if (it.isEmpty()) null else fromFields(it) },
        )
    }
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

    // Kept over a re-created activity, with the form as it was loaded or last saved to tell what is unsaved.
    var form by rememberSaveable(stateSaver = ProfileForm.Saver) { mutableStateOf(ProfileForm()) }
    var baseline by rememberSaveable(stateSaver = ProfileForm.OptionalSaver) { mutableStateOf<ProfileForm?>(null) }

    LaunchedEffect(reload) {
        loaded = Load.Loading
        val result = load { api.profile() }
        // A restored form is what was typed: the load must not replace it. The form is set before the
        // fields are shown, so that nothing typed in the first moment is overwritten.
        if (result is Load.Ready && baseline == null) {
            form = ProfileForm.of(result.value.profile, t.locale)
            baseline = form
        }
        loaded = result
    }
    val unsaved = form != (baseline ?: ProfileForm())
    val leave = UnsavedChangesGuard(unsaved, onBack)

    fun number(text: String) = text.trim().replace(',', '.').toDoubleOrNull()
    val overridesValid = listOf(form.kcalOverride, form.proteinOverride, form.fibreOverride).all { it.isBlank() || it.trim().toIntOrNull() != null }
    val draft = run {
        val w = number(form.weight) ?: return@run null
        val h = number(form.height) ?: return@run null
        val a = form.age.trim().toIntOrNull() ?: return@run null
        val g = number(form.goal) ?: return@run null
        if (!overridesValid) return@run null
        fun words(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        Profile(
            w, h, a, form.sex, form.activity, g, form.kcalOverride.trim().toIntOrNull(), form.proteinOverride.trim().toIntOrNull(),
            words(form.likes), words(form.dislikes), priority = form.priority, fibreOverrideG = form.fibreOverride.trim().toIntOrNull(),
        )
    }
    val problems = draft?.let(TargetCalculator::problems)
    // Apart from the draft, so that a range being typed does not hide the targets.
    val portions = run {
        val min = number(form.minPortions) ?: return@run null
        val max = number(form.maxPortions) ?: return@run null
        PortionRange(min, max)
    }
    val portionProblems = portions?.problems().orEmpty()

    SubScreen(t(CommonMsg.PROFILE_AND_TARGETS), leave, snackbar) { padding ->
        when (val current = loaded) {
            Load.Loading -> LoadingState(Modifier.padding(padding))
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage(t(AccountsMsg.PROFILE_LOAD_FAILED), current) { reload++ }
            }
            is Load.Ready -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.ScreenEdge),
                verticalArrangement = Arrangement.spacedBy(Spacing.BetweenCards),
            ) {
                NumberField(t(AccountsMsg.WEIGHT_KG), form.weight, decimal = true) { form = form.copy(weight = it) }
                NumberField(t(AccountsMsg.HEIGHT_CM), form.height, decimal = true) { form = form.copy(height = it) }
                NumberField(t(AccountsMsg.AGE), form.age, decimal = false) { form = form.copy(age = it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    FilterChip(selected = form.sex == Sex.MALE, onClick = { form = form.copy(sex = Sex.MALE) }, label = { Text(t(AccountsMsg.MALE)) })
                    FilterChip(selected = form.sex == Sex.FEMALE, onClick = { form = form.copy(sex = Sex.FEMALE) }, label = { Text(t(AccountsMsg.FEMALE)) })
                }
                Text(t(AccountsMsg.ACTIVITY), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    ActivityLevel.entries.forEach { level ->
                        FilterChip(selected = form.activity == level, onClick = { form = form.copy(activity = level) }, label = { Text(t(level.msg())) })
                    }
                }
                NumberField(t(AccountsMsg.GOAL_PER_WEEK), form.goal, decimal = true) { form = form.copy(goal = it) }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.InsideCard), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                        Text(t(AccountsMsg.DAILY_TARGETS), style = MaterialTheme.typography.labelLarge)
                        when {
                            draft == null -> Text(t(AccountsMsg.FILL_IN))
                            !problems.isNullOrEmpty() -> Text(problems.joinToString("\n") { t.profileProblem(it) }, color = MaterialTheme.colorScheme.error)
                            else -> {
                                val targets = TargetCalculator.targets(draft)
                                Text(
                                    macrosText(targets.kcal.toDouble(), targets.proteinG.toDouble(), targets.fibreG?.toDouble(), t),
                                    style = MaterialTheme.typography.titleLarge.numeric(),
                                )
                                Text(
                                    TargetCalculator.suggest(draft).let { t(AccountsMsg.MAINTENANCE, TargetCalculator.maintenanceKcal(draft), it.kcal, it.proteinG, it.fibreG) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(t(AccountsMsg.OWN_TARGETS), style = MaterialTheme.typography.labelLarge)
                NumberField(t(AccountsMsg.CALORIES_PER_DAY), form.kcalOverride, decimal = false) { form = form.copy(kcalOverride = it) }
                NumberField(t(AccountsMsg.PROTEIN_PER_DAY), form.proteinOverride, decimal = false) { form = form.copy(proteinOverride = it) }
                NumberField(t(AccountsMsg.FIBRE_PER_DAY), form.fibreOverride, decimal = false) { form = form.copy(fibreOverride = it) }

                Text(t(AccountsMsg.PRIORITY), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    PlanPriority.entries.forEach { priority ->
                        FilterChip(selected = form.priority == priority, onClick = { form = form.copy(priority = priority) }, label = { Text(t(priority.msg())) })
                    }
                }
                Text(t(AccountsMsg.PRIORITY_HINT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text(t(AccountsMsg.PORTION_PER_MEAL), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Related)) {
                    NumberField(t(AccountsMsg.PORTION_FROM), form.minPortions, decimal = true, Modifier.weight(1f)) { form = form.copy(minPortions = it) }
                    NumberField(t(AccountsMsg.PORTION_TO), form.maxPortions, decimal = true, Modifier.weight(1f)) { form = form.copy(maxPortions = it) }
                }
                Text(
                    if (portionProblems.isEmpty()) t(AccountsMsg.PORTION_HINT) else portionProblems.joinToString("\n") { t.profileProblem(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (portions == null || portionProblems.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(t(AccountsMsg.TASTE), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    form.likes, { form = form.copy(likes = it) },
                    label = { Text(t(AccountsMsg.LIKES)) },
                    supportingText = { Text(t(AccountsMsg.LIKES_HINT)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    form.dislikes, { form = form.copy(dislikes = it) },
                    label = { Text(t(AccountsMsg.DISLIKES)) },
                    supportingText = { Text(t(AccountsMsg.DISLIKES_HINT)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    enabled = draft != null && problems.isNullOrEmpty() && portions != null && portionProblems.isEmpty(),
                    onClick = {
                        val saving = form
                        val profile = draft!!.copy(minPortions = portions!!.min, maxPortions = portions.max)
                        scope.launch {
                            when (val result = load { api.saveProfile(profile) }) {
                                is Load.Ready -> {
                                    loaded = result
                                    baseline = saving
                                    snackbar.showSnackbar(t(AccountsMsg.PROFILE_SAVED))
                                }
                                is Load.Failed -> snackbar.showSnackbar(failureSentence(result, CommonMsg.SAVE_FAILED))
                                Load.Loading -> Unit
                            }
                        }
                    },
                ) { Text(t(CommonMsg.SAVE)) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, decimal: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}
