package dev.tobins.mealplanner.server.recipes

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the picture a recipe page of a cooking site (a Hugo site) shows, as a file in the checkout.
 * The first of:
 * 1. the front matter's featured picture: `featured_image`, `image`, `cover` (or `cover.image`), or
 *    the first of `images`;
 * 2. the first picture in the body: the geekdoc theme's `{{< img name="…" >}}` (what the owner's site
 *    uses), Hugo's `{{< figure src="…" >}}`, or Markdown's `![…](…)`;
 * 3. the first picture file, by name, in the page's bundle (the folder of a page that is an `index.md`).
 *
 * A reference is the name of one of the front matter's `resources` (as `img` uses it), a path
 * relative to the page's folder, or a path from the site's root, looked up in `static/` and then
 * `assets/`. Pictures on other sites are not fetched. Nothing outside the checkout is read, also not
 * through a symbolic link or `..`: one account's site must not be able to show the server's files or
 * another account's pictures.
 */
class PagePictureFinder(repoDir: File) {
    private val root: Path = repoDir.toPath().toRealPath()

    fun find(page: File, text: String): File? {
        val (frontMatter, body) = split(text)
        val resources = resourcesOf(frontMatter)
        val references = featuredOf(frontMatter) + bodyPictures.findAll(body).map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
        return references.firstNotNullOfOrNull { resolve(it, page.parentFile, resources) }
            ?: page.takeIf { it.name == "index.md" }?.let(::firstInBundle)
    }

    /** The front matter as YAML, or null without any (or when it is not YAML), and the rest of the page. */
    private fun split(text: String): Pair<YamlMap?, String> {
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull()?.trim() != "---") return null to text
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: return null to text
        val yaml = runCatching { lenientYaml.parseToYamlNode(lines.subList(1, end).joinToString("\n")) as? YamlMap }.getOrNull()
        return yaml to lines.drop(end + 1).joinToString("\n")
    }

    private fun featuredOf(frontMatter: YamlMap?): List<String> {
        if (frontMatter == null) return emptyList()
        val cover = frontMatter.get<YamlNode>("cover")
        return listOfNotNull(
            frontMatter.text("featured_image"),
            frontMatter.text("image"),
            (cover as? YamlScalar)?.content ?: (cover as? YamlMap)?.text("image"),
            (frontMatter.get<YamlNode>("images") as? YamlList)?.items?.firstOrNull()?.let { (it as? YamlScalar)?.content },
        )
    }

    /** Hugo's page resources: their `name` to their `src`, the file in the bundle. */
    private fun resourcesOf(frontMatter: YamlMap?): Map<String, String> =
        (frontMatter?.get<YamlNode>("resources") as? YamlList)?.items.orEmpty()
            .mapNotNull { it as? YamlMap }
            .mapNotNull { resource -> resource.text("name")?.let { name -> resource.text("src")?.let { name to it } } }
            .toMap()

    private fun resolve(reference: String, folder: File, resources: Map<String, String>): File? {
        val path = (resources[reference] ?: reference).substringBefore('?').substringBefore('#').trim()
        if (path.isEmpty() || ':' in path || path.startsWith("//")) return null
        val candidates = if (path.startsWith("/")) {
            listOf(root.resolve("static").toFile(), root.resolve("assets").toFile()).map { File(it, path.trimStart('/')) }
        } else {
            listOf(File(folder, path))
        }
        return candidates.firstNotNullOfOrNull(::inCheckout)
    }

    private fun firstInBundle(page: File): File? =
        page.parentFile.listFiles().orEmpty()
            .filter { it.extension.lowercase() in pictureExtensions }
            .sortedBy { it.name }
            .firstNotNullOfOrNull(::inCheckout)

    /** The file, if it is a regular file that really lies inside the checkout. */
    private fun inCheckout(file: File): File? {
        val real = runCatching { file.toPath().toRealPath() }.getOrNull() ?: return null
        return real.takeIf { it.startsWith(root) && Files.isRegularFile(it) }?.toFile()
    }

    private fun YamlMap.text(key: String): String? = get<YamlScalar>(key)?.content?.takeIf { it.isNotBlank() }

    private companion object {
        val lenientYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        /** `{{< img name="…" >}}`, `{{< figure src="…" >}}` (also with `{{%`) and `![…](…)`, in the order they appear. */
        val bodyPictures = Regex(
            """\{\{[<%]\s*img\s[^}]*?name\s*=\s*"([^"]+)"""" +
                """|\{\{[<%]\s*figure\s[^}]*?src\s*=\s*"([^"]+)"""" +
                """|!\[[^\]]*]\(\s*<?([^)\s>]+)""",
        )

        val pictureExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}
