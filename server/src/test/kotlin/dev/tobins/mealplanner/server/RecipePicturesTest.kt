package dev.tobins.mealplanner.server

import dev.tobins.mealplanner.api.RecipeDetail
import dev.tobins.mealplanner.api.RecipeInput
import dev.tobins.mealplanner.api.RecipeSummary
import dev.tobins.mealplanner.api.SyncResult
import dev.tobins.mealplanner.server.PictureFixtures.jpeg
import dev.tobins.mealplanner.server.PictureFixtures.page
import dev.tobins.mealplanner.server.PictureFixtures.png
import dev.tobins.mealplanner.server.PictureFixtures.pngClaiming
import dev.tobins.mealplanner.server.PictureFixtures.site
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.awt.Color
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pictures from the cooking site: which one a page shows, the copies a sync keeps, and how they are served. */
class RecipePicturesTest {
    private fun picturesOf(dir: File): Map<String, String?> {
        val content = File(dir, "content")
        return CookingSiteReader(dir).readAll()!!.associate { it.recipe.path to it.picture?.relativeTo(dir.canonicalFile)?.invariantSeparatorsPath }
            .also { assertTrue(content.isDirectory) }
    }

    @Test
    fun findsThePictureAPageShows() {
        val outside = createTempDirectory("outside").toFile().resolve("secret.jpg").apply { writeBytes(jpeg(4, 4)) }
        val dir = site(
            // The owner's site: a page bundle whose resources the geekdoc `img` shortcode names.
            "content/recipes/pizza/index.md" to page(
                "Pizza",
                "resources:\n  - name: pizza-1\n    src: \"pizza.jpg\"\n  - name: pizza-2\n    src: \"pizza2.jpg\"",
                "Bake.\n{{< img name=\"pizza-2\" lazy=false >}}\n{{< img name=\"pizza-1\" >}}",
            ),
            "content/recipes/pizza/pizza.jpg" to jpeg(4, 4),
            "content/recipes/pizza/pizza2.jpg" to jpeg(4, 4),
            // A featured picture in the front matter, from the site's root, before one in the body.
            "content/soup/tomato.md" to page("Tomato soup", "image: /images/tomato.png", "![Bowl](bowl.jpg)"),
            "static/images/tomato.png" to png(4, 4),
            "content/soup/bowl.jpg" to jpeg(4, 4),
            "content/wok/noodles/index.md" to page("Noodles", "cover:\n  image: cover.jpg"),
            "content/wok/noodles/cover.jpg" to jpeg(4, 4),
            "content/salads/greek.md" to page("Greek salad", body = "Mix.\n\n![Greek salad](greek.jpg \"title\")"),
            "content/salads/greek.jpg" to jpeg(4, 4),
            "content/sandwiches/club.md" to page("Club", body = "{{< figure src=\"/club.jpg\" >}}"),
            "assets/club.jpg" to jpeg(4, 4),
            // No reference: the first picture of the bundle, by name.
            "content/bbq/ribs/index.md" to page("Ribs"),
            "content/bbq/ribs/b.jpg" to jpeg(4, 4),
            "content/bbq/ribs/a.png" to png(4, 4),
            "content/bbq/ribs/notes.txt" to "not a picture",
            // Nothing from elsewhere: not the web, not outside the checkout.
            "content/baking/bread.md" to page("Bread", "image: https://example.org/bread.jpg"),
            "content/baking/escape.md" to page("Escape", "image: ../../../${outside.parentFile.name}/secret.jpg"),
            "content/baking/rooted.md" to page("Rooted", "image: /../../${outside.parentFile.name}/secret.jpg"),
            "content/baking/linked/index.md" to page("Linked"),
            "content/baking/plain.md" to page("Plain"),
        )
        Files.createSymbolicLink(File(dir, "content/baking/linked/photo.jpg").toPath(), outside.toPath())
        assertEquals(dir.parentFile.canonicalFile, outside.parentFile.parentFile.canonicalFile, "the escapes above point at it")

        assertEquals(
            mapOf(
                "recipes/pizza/index.md" to "content/recipes/pizza/pizza2.jpg",
                "soup/tomato.md" to "static/images/tomato.png",
                "wok/noodles/index.md" to "content/wok/noodles/cover.jpg",
                "salads/greek.md" to "content/salads/greek.jpg",
                "sandwiches/club.md" to "assets/club.jpg",
                "bbq/ribs/index.md" to "content/bbq/ribs/a.png",
                "baking/bread.md" to null,
                "baking/escape.md" to null,
                "baking/rooted.md" to null,
                "baking/linked/index.md" to null,
                "baking/plain.md" to null,
            ),
            picturesOf(dir),
        )
    }

    @Test
    fun readsTheExifOrientationEitherByteOrder() {
        assertEquals(6, exifOrientation(jpeg(8, 4, orientation = 6)))
        assertEquals(1, exifOrientation(jpeg(8, 4)), "none: as stored")
        assertEquals(1, exifOrientation(png(4, 4)), "not a JPEG")
        assertEquals(1, exifOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())), "cut short")
        // Intel byte order, as some cameras write it.
        val intel = jpeg(8, 4, orientation = 8).let { bytes ->
            val tiff = String(bytes, Charsets.ISO_8859_1).indexOf("MM\u0000*")
            bytes.copyOf().also {
                val little = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 8, 0, 0, 0)
                little.copyInto(it, tiff)
            }
        }
        assertEquals(8, exifOrientation(intel))
    }

    private val siteFiles = arrayOf(
        // A phone photo: stored sideways, with EXIF saying to turn it a quarter clockwise.
        "content/recipes/pizza/index.md" to page("Pizza", "resources:\n  - name: pizza-1\n    src: pizza.jpg", "{{< img name=\"pizza-1\" >}}"),
        "content/recipes/pizza/pizza.jpg" to jpeg(1600, 1200, orientation = 6),
        "content/soup/tomato.md" to page("Tomato soup", "image: /images/tomato.png"),
        "static/images/tomato.png" to png(40, 30),
        "content/salads/greek.md" to page("Greek salad", body = "![](greek.jpg)"),
        "content/salads/greek.jpg" to "this is no picture",
        "content/bbq/huge/index.md" to page("Huge"),
        "content/bbq/huge/huge.png" to pngClaiming(20_000, 20_000),
        "content/baking/bread.md" to page("Bread"),
    )

    @Test
    fun aSyncKeepsWebSizedCopiesAndServesThemWithLongCaching() = testApplication {
        val images = createTempDirectory("images").toFile()
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site(*siteFiles), imageDir = images)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }

        assertEquals(5, http.post("/api/sync/cooking").body<SyncResult>().added)
        val manual = http.post("/api/recipes") {
            contentType(ContentType.Application.Json)
            setBody(RecipeInput("Own stew", 2, "400 | g | beef"))
        }.body<RecipeDetail>().summary
        assertNull(manual.imageUrl, "recipes made in the app have no picture")
        val recipes = http.get("/api/recipes").body<List<RecipeSummary>>().associateBy { it.title }
        // Unreadable, implausibly large, or none at all: no picture, and the recipes import all the same.
        for (title in listOf("Greek salad", "Huge", "Bread")) assertNull(recipes.getValue(title).imageUrl, title)

        val pizza = recipes.getValue("Pizza")
        assertTrue(Regex("/api/recipes/${pizza.id}/image\\?v=[0-9a-f]{16}").matches(pizza.imageUrl!!), pizza.imageUrl)
        assertEquals(pizza.imageUrl, http.get("/api/recipes/${pizza.id}").body<RecipeDetail>().summary.imageUrl)
        // Asked as phones ask: a JPEG does not shrink by gzip, so it is sent as it is.
        val response = http.get(pizza.imageUrl!!) { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.JPEG, response.contentType()?.withoutParameters())
        assertEquals("private, max-age=31536000, immutable", response.headers[HttpHeaders.CacheControl])
        assertNull(response.headers[HttpHeaders.ContentEncoding])
        // Turned upright and scaled to 800 pixels on its longest side; the red corner is now top right.
        val photo = ImageIO.read(response.readRawBytes().inputStream())
        assertEquals(600 to 800, photo.width to photo.height)
        assertTrue(Color(photo.getRGB(500, 100)).red > 200, "top right is red")
        assertTrue(Color(photo.getRGB(100, 100)).blue > 200, "top left is blue")

        // Small pictures keep their size; transparency is on white.
        val tomato = ImageIO.read(http.get(recipes.getValue("Tomato soup").imageUrl!!).readRawBytes().inputStream())
        assertEquals(40 to 30, tomato.width to tomato.height)
        assertEquals(listOf(File(images, "1/${pizza.id}.jpg"), File(images, "1/${recipes.getValue("Tomato soup").id}.jpg")).toSet(), File(images, "1").listFiles()!!.toSet())

        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/${recipes.getValue("Bread").id}/image").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/${manual.id}/image").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/recipes/99999/image").status)
        assertEquals(HttpStatusCode.Unauthorized, createClient {}.get(pizza.imageUrl!!).status)
    }

    @Test
    fun aSyncDoesNotFailWhenPicturesCannotBeStored() = testApplication {
        // A file where the folder should be: no copy can be written.
        val images = kotlin.io.path.createTempFile("images").toFile()
        val config = ServerConfig(port = 0, databaseUrl = "jdbc:sqlite::memory:", cookingRepo = site(*siteFiles), imageDir = images)
        application { module(config, openDatabase(config.databaseUrl).second, offerSources = emptyList(), priceSource = null, generator = null, verifier = TestTokens) }
        val http = createClient {
            install(ContentNegotiation) { json() }
            defaultRequest { bearerAuth("t") }
        }

        val sync = http.post("/api/sync/cooking")
        assertEquals(HttpStatusCode.OK, sync.status)
        assertEquals(5, sync.body<SyncResult>().added)
        assertTrue(http.get("/api/recipes").body<List<RecipeSummary>>().all { it.imageUrl == null }, "nothing stored, nothing promised")
    }

    /** An account with the cooking site in [dir], synced the way [CookingSync] does it. */
    private class Account(val dir: File) {
        val images: File = createTempDirectory("images").toFile()
        private val db = openDatabase("jdbc:sqlite::memory:").second
        private val foods = FoodRepository(db)
        private val offers = OfferRepository(db, emptyList())
        val user = UserScope(UserRepository(db).resolve(Identity("a", "alice")).id, db, foods, offers, PriceRepository(db, null, foods, offers), null, images)

        fun sync(): RecipePictures.Outcome {
            val pages = CookingSiteReader(dir).readAll()!!
            user.recipes.syncCookingSite(pages)
            return user.pictures.sync(pages)
        }

        fun summary(title: String) = user.recipes.list().single { it.title == title }
    }

    @Test
    fun aLaterSyncReplacesChangedPicturesKeepsUnchangedOnesAndRemovesGoneOnes() {
        val account = Account(site(*siteFiles, "content/wok/noodles/index.md" to page("Noodles"), "content/wok/noodles/noodles.jpg" to jpeg(100, 50)))
        assertEquals(RecipePictures.Outcome(stored = 3, unchanged = 0, removed = 0, skipped = 2), account.sync())
        val pizza = account.summary("Pizza")
        val noodles = account.summary("Noodles")
        val tomato = account.summary("Tomato soup")
        val noodlesCopy = account.user.pictures.find(noodles.id)!!
        noodlesCopy.setLastModified(1_000_000_000_000)

        // A new photo of the pizza; the soup and its page are gone; the salad's page drops its broken picture.
        PictureFixtures.write(account.dir, "content/recipes/pizza/pizza.jpg", jpeg(1600, 1200, orientation = 6, background = Color.GREEN))
        File(account.dir, "content/soup/tomato.md").delete()
        PictureFixtures.write(account.dir, "content/salads/greek.md", page("Greek salad", body = "No picture yet."))
        val outcome = account.sync()

        // The unusable "huge" picture was reported the first time and is not tried (or logged) again.
        assertEquals(RecipePictures.Outcome(stored = 1, unchanged = 2, removed = 1, skipped = 0), outcome)
        val newPizza = account.summary("Pizza")
        assertNotEquals(pizza.imageUrl, newPizza.imageUrl, "a new URL for the new picture")
        val copy = ImageIO.read(account.user.pictures.find(pizza.id))
        assertTrue(Color(copy.getRGB(100, 700)).green > 200, "the new photo")
        assertEquals(noodles.imageUrl, account.summary("Noodles").imageUrl)
        assertEquals(1_000_000_000_000, noodlesCopy.lastModified(), "not written again")
        assertNull(account.user.pictures.find(tomato.id), "the removed recipe's picture is gone")
        assertFalse(File(account.images, "1/${tomato.id}.jpg").exists())
        assertNull(account.summary("Greek salad").imageUrl)

        // The page comes back: so does its picture.
        PictureFixtures.write(account.dir, "content/soup/tomato.md", page("Tomato soup", "image: /images/tomato.png"))
        assertEquals(1, account.sync().stored)
        assertNotNull(account.user.pictures.find(tomato.id))

        // A copy lost from the disk is made again; stray files are cleaned up.
        account.user.pictures.find(pizza.id)!!.delete()
        File(account.images, "1/12345.jpg").writeText("stray")
        assertEquals(RecipePictures.Outcome(stored = 1, unchanged = 3, removed = 1, skipped = 0), account.sync())
        assertNotNull(account.user.pictures.find(pizza.id))
        assertEquals(newPizza.imageUrl, account.summary("Pizza").imageUrl, "the same picture keeps its URL")

        // Changing the site forgets every picture until the next sync.
        account.user.pictures.removeAll()
        assertTrue(account.user.recipes.list().all { it.imageUrl == null })
        assertFalse(File(account.images, "1").exists())
    }
}
