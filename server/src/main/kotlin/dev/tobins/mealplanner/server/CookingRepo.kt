package dev.tobins.mealplanner.server

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import dev.tobins.mealplanner.units.Dimension
import dev.tobins.mealplanner.units.UnitDef
import dev.tobins.mealplanner.units.UnitTable
import kotlinx.serialization.Serializable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File

/**
 * Keeps a local checkout of the cooking repo up to date. With no [url] the directory is
 * used as it is (a checkout managed by hand); with one, it is cloned on first use and
 * reset to the remote branch on every [update], so local edits there are discarded.
 */
class CookingRepo(val dir: File, val url: String?) {

    /**
     * Brings the checkout up to date with the repo's default branch (whatever it is called).
     * @return the commit now checked out, or null without [url].
     */
    fun update(): String? {
        if (url == null) return null
        if (!File(dir, ".git").isDirectory) {
            dir.mkdirs()
            Git.cloneRepository().setURI(url).setDirectory(dir).call().close()
        }
        Git.open(dir).use { git ->
            git.fetch().setRemote("origin").call()
            // The clone checked out the default branch; follow that branch on the remote.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/${git.repository.branch}").call()
            return git.repository.resolve("HEAD")?.name
        }
    }

    /** The repo's unit table (`data/units.yaml`), or the built-in copy when the file is missing. */
    fun units(): UnitTable = File(dir, "data/units.yaml").takeIf { it.isFile }?.let { parseUnits(it.readText()) } ?: UnitTable.Default
}

@Serializable
private data class UnitYaml(
    val factor: Double = 1.0,
    val system: String? = null,
    val abbr: String? = null,
    val aliases: List<String> = emptyList(),
)

private val lenientYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

/**
 * Reads the cooking repo's `data/units.yaml`: a map from dimension (`weight`, `volume`,
 * `count`) to units, each with a factor to grams or millilitres and its aliases.
 */
fun parseUnits(yaml: String): UnitTable {
    val dimensions = lenientYaml.decodeFromString(
        kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer<String>(),
            kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), UnitYaml.serializer()),
        ),
        yaml,
    )
    return UnitTable(
        dimensions.flatMap { (dimension, units) ->
            val kind = when (dimension.lowercase()) {
                "weight" -> Dimension.WEIGHT
                "volume" -> Dimension.VOLUME
                "count" -> Dimension.COUNT
                else -> throw IllegalArgumentException("Unknown unit dimension '$dimension' in units.yaml")
            }
            units.map { (key, unit) -> UnitDef(key, kind, if (kind == Dimension.COUNT) 1.0 else unit.factor, unit.aliases) }
        },
    )
}
