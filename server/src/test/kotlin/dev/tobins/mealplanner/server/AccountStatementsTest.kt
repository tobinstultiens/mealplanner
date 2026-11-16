package dev.tobins.mealplanner.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every SQL statement on an account's table names the account (issue 132).
 *
 * Accounts are kept apart by the statements themselves: a repository is made for one account and each
 * statement on that account's rows filters by its id. A new statement that forgets it, such as
 * `DELETE FROM shoppingExtra WHERE id = ?`, compiles and passes every other test, and lets one account
 * change another's rows. This test reads every `.sq` file, so a statement or a file added later is checked
 * as well, and fails on each statement that names an account's table but not `userId`.
 *
 * It checks the text, it does not prove anything: `userId` in a SELECT list would pass. It catches the
 * forgotten condition, which is the likely mistake.
 */
class AccountStatementsTest {
    private val schema = Schema.read(File("src/main/sqldelight"))

    @Test
    fun everyStatementOnAnAccountsTableNamesTheAccount() {
        assertTrue(schema.tables.any { it.hasUserId }, "no table with a userId column was found: the files were not read")
        val missing = schema.statementsMissingTheAccount().filter { it.key !in ALLOWED }
        assertTrue(missing.isEmpty(), missing.joinToString("\n\n") { schema.complaint(it) })
    }

    @Test
    fun everyTableBelowAnAccountsTableIsListed() {
        val parents = schema.accountTables + ACCOUNTS.lowercase()
        val unlisted = schema.tables.filter { !it.hasUserId && it.key !in HANGS_OFF.keys.map(String::lowercase) && it.references.any { r -> r in parents } }
        assertTrue(unlisted.isEmpty(), unlisted.joinToString("\n\n") { table ->
            "${table.file}, table ${table.name}: has no userId column but hangs off ${table.references.intersect(parents).joinToString()}, " +
                "so its rows belong to an account. Add a userId column, or list it in HANGS_OFF in AccountStatementsTest " +
                "with the table its account is reached through; its statements are then checked like the others."
        })
    }

    /** A list that names something no longer there, or no longer needed, would hide what it was for. */
    @Test
    fun theListsNameOnlyWhatIsStillNeeded() {
        assertTrue(schema.tables.any { it.key == ACCOUNTS.lowercase() }, "no table $ACCOUNTS")
        val missing = schema.statementsMissingTheAccount().map { it.key }.toSet()
        for ((file, name) in ALLOWED.keys) {
            assertTrue(schema.statements.any { it.key == file to name }, "ALLOWED names $file $name, which is gone: take it off the list")
            assertTrue(file to name in missing, "ALLOWED names $file $name, which names the account now: take it off the list")
        }
        for (name in HANGS_OFF.keys) {
            val table = assertNotNull(schema.tables.firstOrNull { it.key == name.lowercase() }, "HANGS_OFF names table $name, which is gone: take it off the list")
            assertTrue(!table.hasUserId, "HANGS_OFF names table $name, which has a userId column now: take it off the list")
        }
    }

    @Test
    fun aStatementThatForgetsTheAccountIsCaught() {
        val careless = Statement("Shopping.sq", "deleteAnyExtra", "DELETE FROM shoppingExtra WHERE id = ?")
        val withIt = schema.copy(statements = listOf(careless))

        assertEquals(listOf(careless), withIt.statementsMissingTheAccount())
        val complaint = withIt.complaint(careless)
        assertTrue("Shopping.sq, deleteAnyExtra" in complaint, complaint)
        assertTrue("names shoppingExtra" in complaint, complaint)
        assertTrue("add it to ALLOWED in AccountStatementsTest with a reason" in complaint, complaint)
    }

    @Test
    fun commentsAndTextsDoNotNameTheAccount() {
        val parsed = Schema.parse(
            "Shopping.sq",
            """
            -- Leaves out the userId on purpose.
            deleteByName:
            DELETE FROM shoppingExtra /* userId */ WHERE name = 'userId';

            deleteOwn:
            DELETE FROM shoppingExtra WHERE userId = ? AND name = 'it''s; done';
            """.trimIndent(),
        )
        val withThem = schema.copy(statements = parsed.statements)

        assertEquals(listOf("deleteByName", "deleteOwn"), parsed.statements.map { it.name })
        assertEquals(listOf("deleteByName"), withThem.statementsMissingTheAccount().map { it.name })
    }

    @Test
    fun tablesAreFoundWhereverAStatementNamesThem() {
        assertEquals(setOf("a", "b", "c"), tablesNamedIn("SELECT * FROM a x, b AS y, main.c WHERE x.id = y.id"))
        assertEquals(setOf("shoppingcheck"), tablesNamedIn("UPDATE OR IGNORE shoppingCheck SET itemKey = ?"))
        assertEquals(setOf("onhand", "stock", "ingredient"), tablesNamedIn(
            "INSERT OR IGNORE INTO onHand (ingredientId, addedAt) SELECT id, (SELECT min(addedAt) FROM stock) FROM ingredient",
        ))
        assertEquals(setOf("recipeingredient", "recipe"), tablesNamedIn("SELECT * FROM recipeIngredient ri JOIN recipe r ON r.id = ri.recipeId"))
        assertEquals(setOf("stock", "ingredient"), tablesNamedIn("DELETE FROM stock WHERE ingredientId IN ingredient"))
        assertEquals(setOf("stock"), tablesNamedIn("SELECT * FROM stock WHERE ingredientId IN :ids ORDER BY fromWeek, source"))
        // offerHistory has a column called ingredient; naming it does not name the ingredient table.
        assertEquals(setOf("offerhistory"), tablesNamedIn("UPDATE offerHistory SET ingredient = :ingredient WHERE id = :id"))
    }

    @Test
    fun groupedStatementsAreCheckedOneByOne() {
        val parsed = Schema.parse(
            "Shopping.sq",
            """
            clearWeek {
                DELETE FROM shoppingCheck WHERE userId = :userId AND weekStart = :week;
                DELETE FROM shoppingExtra WHERE fromWeek = :week;
            }
            """.trimIndent(),
        )
        val withThem = schema.copy(statements = parsed.statements)

        assertEquals(listOf("clearWeek (statement 2)"), withThem.statementsMissingTheAccount().map { it.name })
    }
}

/** The accounts themselves: a table that points at it without a userId column hangs off it. */
private const val ACCOUNTS = "appUser"

/**
 * Tables of one account that have no userId column of their own: a statement on them names the
 * account through the table they hang off, e.g. `ingredientId IN (SELECT id FROM ingredient WHERE userId = ?)`.
 */
private val HANGS_OFF = mapOf(
    "recipeIngredient" to "a recipe's lines, of the account through recipe.userId",
    "onHand" to "put on hand by hand, of the account through ingredient.userId",
    "stock" to "what the shopping list put at home, of the account through ingredient.userId",
)

/**
 * Statements on an account's table that do not name the account, each with the reason that is safe.
 * Keyed by file and statement name.
 */
private val ALLOWED = mapOf(
    ("Recipe.sq" to "selectIngredients") to
        "takes a recipe id that RecipeRepository read with the account's id first (selectById(userId, id) or selectActive(userId))",
    ("Recipe.sq" to "deleteIngredients") to
        "RecipeRepository.replaceIngredients, called only with a recipe id read with the account's id or just inserted for it",
    ("Recipe.sq" to "insertIngredient") to
        "RecipeRepository.replaceIngredients, called only with a recipe id read with the account's id or just inserted for it",
    ("Ingredient.sq" to "selectEveryIngredient") to
        "the shared price lookup and price history read every account's ingredient names on purpose",
    ("Ingredient.sq" to "selectEveryAlias") to
        "the shared price lookup and price history read every account's ingredient names on purpose",
)

/** A labelled statement of a `.sq` file, without its comments. */
private data class Statement(val file: String, val name: String, val sql: String) {
    val key get() = file to name
}

/** A table or view; [references] are the tables it points at, in lower case as SQLite compares them. */
private data class Table(val file: String, val name: String, val hasUserId: Boolean, val references: Set<String>) {
    val key get() = name.lowercase()
}

private data class Schema(val tables: List<Table>, val statements: List<Statement>) {
    /** The tables of one account, in lower case: those with a userId column and those the test lists. */
    val accountTables: Set<String> get() = tables.filter { it.hasUserId }.map { it.key }.toSet() + HANGS_OFF.keys.map { it.lowercase() }

    fun statementsMissingTheAccount(): List<Statement> =
        statements.filter { s -> tablesNamedIn(s.sql).any { it in accountTables } && !namesTheAccount(s.sql) }

    fun complaint(s: Statement): String {
        val named = tablesNamedIn(s.sql).filter { it in accountTables }.map { key -> tables.firstOrNull { it.key == key }?.name ?: key }
        return "${s.file}, ${s.name}: names ${named.joinToString()}, a table of one account, but not userId, so it can reach " +
            "another account's rows.\n" +
            "  Add the account to it (WHERE userId = ?, or through the table it hangs off: " +
            "ingredientId IN (SELECT id FROM ingredient WHERE userId = ?)),\n" +
            "  or, if it must reach every account or is only called with a row the caller read with the account's id, " +
            "add it to ALLOWED in AccountStatementsTest with a reason a reviewer can check."
    }

    companion object {
        fun read(dir: File): Schema {
            val files = dir.walkTopDown().filter { it.isFile && it.extension == "sq" }.sortedBy { it.path }.toList()
            check(files.isNotEmpty()) { "no .sq files under ${dir.absolutePath}" }
            val parts = files.map { parse(it.name, it.readText()) }
            return Schema(parts.flatMap { it.tables }, parts.flatMap { it.statements })
        }

        /** The tables and statements of one `.sq` file. */
        fun parse(file: String, text: String): Schema {
            val tables = mutableListOf<Table>()
            val statements = mutableListOf<Statement>()
            var unlabelled = 0
            for (chunk in chunks(withoutCommentsAndTexts(text))) {
                val group = GROUP.matchEntire(chunk)
                val label = LABEL.matchEntire(chunk)
                val table = CREATE_TABLE.matchEntire(chunk)
                val view = CREATE_VIEW.matchEntire(chunk)
                when {
                    group != null -> group.groupValues[2].split(';').map { it.trim() }.filter { it.isNotEmpty() }
                        .forEachIndexed { i, sql -> statements += Statement(file, "${group.groupValues[1]} (statement ${i + 1})", sql) }
                    label != null -> statements += Statement(file, label.groupValues[1], label.groupValues[2])
                    table != null -> tables += tableOf(file, table.groupValues[1], table.groupValues[2])
                    view != null -> tables += Table(file, view.groupValues[1], hasUserId = false, tablesNamedIn(view.groupValues[2]))
                    chunk.startsWith("CREATE", ignoreCase = true) || chunk.startsWith("import ") -> Unit
                    // SQLDelight runs a statement without a name when it creates the database; it is checked all the same.
                    else -> statements += Statement(file, "unlabelled statement ${++unlabelled}", chunk)
                }
            }
            return Schema(tables, statements)
        }

        private fun tableOf(file: String, name: String, body: String): Table {
            val columns = topLevelParts(body).mapNotNull { part -> tokens(part).firstOrNull()?.takeUnless { it.uppercase() in CONSTRAINTS } }
            val references = REFERENCES.findAll(body).map { it.groupValues[1].lowercase() }.toSet()
            return Table(file, name, columns.any { it.equals("userId", ignoreCase = true) }, references)
        }

        private val GROUP = Regex("""(\w+)\s*\{(.*)}""", RegexOption.DOT_MATCHES_ALL)
        private val LABEL = Regex("""(\w+)\s*:\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
        private val CREATE_TABLE = Regex(
            """CREATE\s+(?:TEMP\w*\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\((.*)\)[^)]*""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val CREATE_VIEW = Regex(
            """CREATE\s+(?:TEMP\w*\s+)?VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s+AS\s+(.*)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val REFERENCES = Regex("""\bREFERENCES\s+(\w+)""", RegexOption.IGNORE_CASE)
        private val CONSTRAINTS = setOf("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "CONSTRAINT")
    }
}

/** Whether a statement mentions the account's id: as a column, `i.userId`, or a parameter, `:userId`. */
private fun namesTheAccount(sql: String) = tokens(sql).any { it.equals("userId", ignoreCase = true) }

/**
 * The tables a statement reads or changes, in lower case: those after FROM (with comma joins), JOIN, INTO,
 * UPDATE and `IN table`. Not just any word that is a table's name, as offerHistory has a column `ingredient`.
 */
private fun tablesNamedIn(sql: String): Set<String> {
    val t = tokens(sql)
    val found = mutableSetOf<String>()
    fun word(i: Int) = t.getOrNull(i)?.uppercase()
    fun isName(i: Int) = t.getOrNull(i)?.first()?.let { it.isLetter() || it == '_' } == true

    /** Takes the table named at [i], `schema.table` too, and gives the place after it; null when there is none. */
    fun tableAt(i: Int): Int? {
        if (!isName(i)) return null
        val at = if (t.getOrNull(i + 1) == "." && isName(i + 2)) i + 2 else i
        found += t[at].lowercase()
        return at + 1
    }

    for (i in t.indices) {
        when (word(i)) {
            "FROM" -> {
                var next = tableAt(i + 1)
                while (next != null) {
                    var k = next
                    if (word(k) == "AS") k += 2 else if (isName(k) && t.getOrNull(k + 1) == ",") k += 1
                    next = if (t.getOrNull(k) == ",") tableAt(k + 1) else null
                }
            }
            "JOIN", "INTO", "IN" -> tableAt(i + 1)
            "UPDATE" -> tableAt(if (word(i + 1) == "OR") i + 3 else i + 1)
        }
    }
    return found
}

/** Names (quoted ones without their quotes) and single other characters; `:userId` is `:` and `userId`. */
private fun tokens(sql: String): List<String> =
    TOKEN.findAll(sql).map { it.value.removeSurrounding("\"").removeSurrounding("`").removePrefix("[").removeSuffix("]") }.toList()

private val TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_$]*|"[^"]*"|`[^`]*`|\[[^\]]*]|\S""")

/** The text of a `.sq` file without comments, and with every quoted text emptied, as either could hold anything. */
private fun withoutCommentsAndTexts(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("--", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            text.startsWith("/*", i) -> {
                i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                append(' ')
            }
            text[i] == '\'' -> {
                var j = i + 1
                // '' inside a text is a quote, not its end.
                while (j < text.length && !(text[j] == '\'' && text.getOrNull(j + 1) != '\'')) j += if (text[j] == '\'') 2 else 1
                append("''")
                i = j + 1
            }
            else -> append(text[i++])
        }
    }
}

/** The file's top-level parts: statements end at `;`, and a group `name { ...; ...; }` at its closing brace. */
private fun chunks(text: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    text.forEachIndexed { i, c ->
        when {
            c == '{' -> depth++
            c == '}' && --depth == 0 -> out += text.substring(start, i + 1).also { start = i + 1 }
            c == ';' && depth == 0 -> out += text.substring(start, i).also { start = i + 1 }
        }
    }
    out += text.substring(start)
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

/** The parts of a table's definition between commas, leaving alone the commas inside brackets. */
private fun topLevelParts(body: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    body.forEachIndexed { i, c ->
        when {
            c == '(' -> depth++
            c == ')' -> depth--
            c == ',' && depth == 0 -> out += body.substring(start, i).also { start = i + 1 }
        }
    }
    out += body.substring(start)
    return out
}
