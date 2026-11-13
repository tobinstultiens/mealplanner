package dev.tobins.mealplanner.server.generation

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Generates recipes with Claude. The reply is constrained to [schema] (structured output),
 * so it always parses; whether it is sensible is checked by [problems] and by computing
 * its nutrition from the catalogue afterwards.
 *
 * Refusal fallbacks are on: should the model decline, the API retries on a fallback
 * model in the same call.
 */
class ClaudeRecipeGenerator(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
) : RecipeGenerator {

    override suspend fun generate(context: GenerationContext): GeneratedRecipe = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000L)
            .system(RecipePrompt.system)
            .addUserMessage(RecipePrompt.user(context))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.MEDIUM)
                    .format(BetaJsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .fallbacksDefault()
            .build()

        val message = try {
            client.beta().messages().create(params)
        } catch (e: AnthropicException) {
            throw GenerationException("The Claude API call failed: ${e.message}", e)
        }
        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> throw GenerationException(
                "Claude declined to write this recipe" +
                    (message.stopDetails().flatMap { it.explanation() }.map { ": $it" }.orElse(".")),
            )
            BetaStopReason.MAX_TOKENS -> throw GenerationException("The recipe was cut off; try a shorter wish.")
            else -> Unit
        }
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        val recipe = try {
            json.decodeFromString<GeneratedRecipe>(text)
        } catch (e: Exception) {
            throw GenerationException("Claude's reply was not a readable recipe", e)
        }
        recipe.problems().takeIf { it.isNotEmpty() }?.let { throw GenerationException("The generated recipe was unusable: ${it.joinToString("; ")}") }
        recipe
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** A generator from `ANTHROPIC_API_KEY`, or null when it is not set. */
        fun fromEnv(env: Map<String, String> = System.getenv()): ClaudeRecipeGenerator? {
            if (env["ANTHROPIC_API_KEY"].isNullOrBlank()) return null
            return ClaudeRecipeGenerator(AnthropicOkHttpClient.fromEnv(), env["MEALPLANNER_MODEL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL)
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun obj(vararg properties: Pair<String, Any>, required: List<String> = properties.map { it.first }) = mapOf(
            "type" to "object",
            "properties" to mapOf(*properties),
            "required" to required,
            "additionalProperties" to false,
        )

        private val stringList = mapOf("type" to "array", "items" to mapOf("type" to "string"))

        /** JSON schema of [GeneratedRecipe]. */
        internal val schemaMap: Map<String, Any> = obj(
            "title" to mapOf("type" to "string"),
            "description" to mapOf("type" to "string"),
            "servings" to mapOf("type" to "integer"),
            "tags" to stringList,
            "ingredients" to mapOf(
                "type" to "array",
                "items" to obj(
                    "amount" to mapOf("type" to listOf("number", "null")),
                    "unit" to mapOf("type" to "string", "enum" to GeneratedIngredient.UNITS),
                    "item" to mapOf("type" to "string"),
                ),
            ),
            "steps" to stringList,
        )

        private val schema: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
            .apply { schemaMap.forEach { (key, value) -> putAdditionalProperty(key, JsonValue.from(value)) } }
            .build()
    }
}
