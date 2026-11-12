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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

private val activityLabels = mapOf(
    ActivityLevel.SEDENTARY to "Mostly sitting",
    ActivityLevel.LIGHT to "Light (1–3 workouts a week)",
    ActivityLevel.MODERATE to "Moderate (3–5 workouts)",
    ActivityLevel.ACTIVE to "Very active (6–7 workouts)",
)

/** Body data and goal; the daily calorie and protein targets follow from them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(api: ApiClient) {
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

    LaunchedEffect(reload) {
        loaded = Load.Loading
        loaded = load { api.profile() }
        (loaded as? Load.Ready)?.value?.profile?.let { profile ->
            weight = formatAmount(profile.weightKg)
            height = formatAmount(profile.heightCm)
            age = profile.ageYears.toString()
            sex = profile.sex
            activity = profile.activity
            goal = formatAmount(profile.goalKgPerWeek)
            kcalOverride = profile.kcalOverride?.toString().orEmpty()
            proteinOverride = profile.proteinOverrideG?.toString().orEmpty()
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
        Profile(w, h, a, sex, activity, g, kcalOverride.trim().toIntOrNull(), proteinOverride.trim().toIntOrNull())
    }
    val problems = draft?.let(TargetCalculator::problems)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Profile") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = loaded) {
            Load.Loading -> CenteredBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                ErrorMessage("Could not load your profile", current.message) { reload++ }
            }
            is Load.Ready -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberField("Weight (kg)", weight, decimal = true) { weight = it }
                NumberField("Height (cm)", height, decimal = true) { height = it }
                NumberField("Age", age, decimal = false) { age = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sex == Sex.MALE, onClick = { sex = Sex.MALE }, label = { Text("Male") })
                    FilterChip(selected = sex == Sex.FEMALE, onClick = { sex = Sex.FEMALE }, label = { Text("Female") })
                }
                Text("Activity", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityLevel.entries.forEach { level ->
                        FilterChip(selected = activity == level, onClick = { activity = level }, label = { Text(activityLabels.getValue(level)) })
                    }
                }
                NumberField("Weight loss per week (kg, 0 to 1)", goal, decimal = true) { goal = it }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Daily targets", style = MaterialTheme.typography.labelLarge)
                        when {
                            draft == null -> Text("Fill in the fields above to see your targets.")
                            !problems.isNullOrEmpty() -> Text(problems.joinToString("\n"), color = MaterialTheme.colorScheme.error)
                            else -> {
                                val targets = TargetCalculator.targets(draft)
                                Text("${targets.kcal} kcal · ${targets.proteinG} g protein", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "You maintain your weight at about ${TargetCalculator.maintenanceKcal(draft)} kcal a day. " +
                                        "Suggested: ${TargetCalculator.suggest(draft).let { "${it.kcal} kcal, ${it.proteinG} g protein" }}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text("Optional: use your own targets instead of the suggestion", style = MaterialTheme.typography.labelLarge)
                NumberField("Calories per day", kcalOverride, decimal = false) { kcalOverride = it }
                NumberField("Protein per day (g)", proteinOverride, decimal = false) { proteinOverride = it }

                Button(
                    enabled = draft != null && problems.isNullOrEmpty(),
                    onClick = {
                        scope.launch {
                            when (val result = load { api.saveProfile(draft!!) }) {
                                is Load.Ready -> {
                                    loaded = result
                                    snackbar.showSnackbar("Profile saved")
                                }
                                is Load.Failed -> snackbar.showSnackbar("Could not save: ${result.message}")
                                Load.Loading -> Unit
                            }
                        }
                    },
                ) { Text("Save") }
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
