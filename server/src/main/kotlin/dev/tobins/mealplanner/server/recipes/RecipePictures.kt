package dev.tobins.mealplanner.server.recipes

import dev.tobins.mealplanner.server.db.Database
import dev.tobins.mealplanner.server.writing
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * One account's recipe pictures: web-sized copies ([WebPicture]) of the pictures its cooking-site
 * pages show, kept as `<root>/<account id>/<recipe id>.jpg`. Those names come from ids in the
 * database, never from a request or a page.
 */
class RecipePictures(private val db: Database, root: File, private val userId: Long) {
    private val q = db.recipeQueries
    private val dir = File(root, userId.toString())

    /** What a sync did, for the log. */
    data class Outcome(val stored: Int, val unchanged: Int, val removed: Int, val skipped: Int)

    /**
     * Brings the copies of the bank's recipes in line with [pages], after [RecipeRepository.syncCookingSite] stored their
     * recipes; a recipe taken from the bank keeps the copy it had, as it keeps the rest of what the site had (issue 340).
     * A picture whose file is unchanged (same SHA-256) is left alone; one that cannot be used
     * is logged and remembered by its hash, so it is logged once and not tried again until it changes.
     * Runs outside a database transaction: a photo takes a moment to scale, and other requests must
     * not wait for the write lock meanwhile. Each picture is stored on its own ([setPicture]).
     *
     * @throws IOException when a copy cannot be written; the next sync tries again.
     */
    fun sync(pages: List<SitePage>): Outcome {
        val pictures = pages.associate { it.recipe.path to it.picture }
        var stored = 0
        var unchanged = 0
        var skipped = 0
        for (row in q.selectBankPictures(userId).executeAsList()) {
            val source = row.sourcePath?.let(pictures::get)
            if (source == null) {
                if (row.imageSourceHash != null) setPicture(row.id, null, null)
                continue
            }
            val hash = try {
                sha256(source)
            } catch (e: IOException) {
                log.warn("Picture of recipe ${row.sourcePath} (account $userId) cannot be read now: ${e.message}")
                continue
            }
            if (hash == row.imageSourceHash && (row.imageVersion == null || copyOf(row.id).isFile)) {
                unchanged++
                continue
            }
            val copy = try {
                WebPicture.of(source)
            } catch (e: UnusablePicture) {
                log.warn("Picture ${source.name} of recipe ${row.sourcePath} (account $userId) is not shown: ${e.message}")
                setPicture(row.id, hash, null)
                skipped++
                continue
            }
            write(row.id, copy)
            setPicture(row.id, hash, sha256(copy).take(VERSION_LENGTH))
            stored++
        }
        return Outcome(stored, unchanged, removed = removeStrays(), skipped)
    }

    /** The copy of the picture of recipe [recipeId]; null when this account has no such recipe, or it has no picture. */
    fun find(recipeId: Long): File? {
        val row = q.selectById(userId, recipeId).executeAsOneOrNull() ?: return null
        if (row.removedAt != null || row.imageVersion == null) return null
        return copyOf(recipeId).takeIf { it.isFile }
    }

    /**
     * Forgets the pictures of the account's bank, when it changes its cooking site. Its own recipes, those taken from
     * the old site among them, keep theirs (issue 340).
     */
    fun removeBank() {
        db.writing { q.clearBankPictures(userId) }
        removeStrays()
    }

    /** Deletes the copy of the picture of recipe [recipeId], once that recipe is deleted; nothing when it had none. */
    fun forget(recipeId: Long) {
        copyOf(recipeId).delete()
    }

    /** Stores what recipe [recipeId]'s picture is now: the [hash] of its file, and the [version] of its copy when it has one. */
    private fun setPicture(recipeId: Long, hash: String?, version: String?) {
        db.writing { q.setPicture(hash, version, userId, recipeId) }
    }

    private fun copyOf(recipeId: Long) = File(dir, "$recipeId.jpg")

    /** Writes next to the copy and renames, so a request never reads half a picture. */
    private fun write(recipeId: Long, bytes: ByteArray) {
        dir.mkdirs()
        val temporary = File(dir, "$recipeId.jpg.tmp")
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), copyOf(recipeId).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Deletes every file no current picture is: those of removed recipes and pictures, and leftovers of
     * an interrupted write. Doing this from the database's state, rather than at each change, also
     * cleans up after a sync that stopped half-way.
     */
    private fun removeStrays(): Int {
        val kept = q.selectKeptPictures(userId).executeAsList().mapTo(HashSet()) { "$it.jpg" }
        return dir.listFiles().orEmpty().count { it.isFile && it.name !in kept && it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val log = LoggerFactory.getLogger(RecipePictures::class.java)

        /** 64 bits of the copy's hash: plenty to tell one recipe's versions apart. */
        const val VERSION_LENGTH = 16
    }
}
