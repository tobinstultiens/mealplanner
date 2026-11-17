package dev.tobins.mealplanner.app

import dev.tobins.mealplanner.api.CookingRepoSetting
import dev.tobins.mealplanner.api.CookingSyncStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeBankSyncTest {
    private val ownStatus = """{"syncedAt":"2026-11-18T10:00:00Z","commit":"d4e5f6a","error":null,"everyHours":6,"repoUrl":"https://example.com/cooking.git"}"""
    private val serverStatus = """{"syncedAt":null,"commit":null,"error":null,"everyHours":6,"repoUrl":"https://example.org/site.git","shared":true,"serverSite":true,"serverRepoUrl":"https://example.org/site.git"}"""
    private val noneStatus = """{"syncedAt":null,"commit":null,"error":null,"everyHours":null,"serverSite":true,"serverRepoUrl":"https://example.org/site.git"}"""

    private fun api(
        asked: MutableList<String>,
        sync: String = """{"added":3,"updated":1,"unchanged":6,"removed":0,"problems":{}}""",
        status: String = ownStatus,
    ) = ApiClient(
        ServerSettings("http://localhost"),
        engine = MockEngine { request ->
            asked += "${request.method.value} ${request.url.encodedPath}"
            val body = if (request.method == HttpMethod.Post) sync else status
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun aNewRepositoryIsSavedAndImportedAndTheOutcomeSaysHowManyAreNewInTheBank() = runTest {
        val asked = mutableListOf<String>()
        var shown: String? = null
        val outcome = saveCookingRepo(api(asked), Strings.English, CookingRepoSetting("https://example.com/cooking.git")) { shown = it.repoUrl }
        assertEquals(listOf("PUT /api/sync/cooking/repo", "POST /api/sync/cooking"), asked)
        assertEquals("https://example.com/cooking.git", shown)
        assertEquals("3 new recipes in the bank", outcome?.message)
        assertEquals(3, outcome?.newInBank)
    }

    @Test
    fun choosingTheServersSiteImportsIt() = runTest {
        val asked = mutableListOf<String>()
        val outcome = saveCookingRepo(api(asked, status = serverStatus), Strings.English, CookingRepoSetting(null)) {}
        assertEquals(listOf("PUT /api/sync/cooking/repo", "POST /api/sync/cooking"), asked)
        assertEquals(3, outcome?.newInBank)
    }

    @Test
    fun choosingNoSiteImportsNothing() = runTest {
        val asked = mutableListOf<String>()
        assertNull(saveCookingRepo(api(asked, status = noneStatus), Strings.English, CookingRepoSetting(null, noSite = true)) {})
        assertEquals(listOf("PUT /api/sync/cooking/repo"), asked)
    }

    @Test
    fun theDialogStartsOnTheChoiceInUseAndSaysNoneAsTheServerUnderstandsIt() {
        val own = CookingSyncStatus(null, null, null, 6, repoUrl = "https://example.com/c.git")
        assertEquals(SiteChoice.OWN, own.siteChoice())
        assertEquals(SiteChoice.SERVER, own.copy(shared = true, serverSite = true).siteChoice())
        assertEquals(SiteChoice.NONE, CookingSyncStatus(null, null, null, null, serverSite = true).siteChoice())

        assertEquals(CookingRepoSetting("https://example.com/c.git"), siteSetting(SiteChoice.OWN, " https://example.com/c.git ", serverSite = true))
        assertEquals(CookingRepoSetting(null), siteSetting(SiteChoice.SERVER, "ignored", serverSite = true))
        assertEquals(CookingRepoSetting(null, noSite = true), siteSetting(SiteChoice.NONE, "", serverSite = true))
        // A server that offers no site of its own, or is from before it could: no URL is none already.
        assertEquals(CookingRepoSetting(null), siteSetting(SiteChoice.NONE, "", serverSite = false))
    }

    @Test
    fun aSyncThatFoundNothingNewSaysWhatItDid() = runTest {
        val outcome = syncCookingSite(api(mutableListOf(), """{"added":0,"updated":2,"unchanged":6,"removed":1,"problems":{}}"""), Strings.Dutch, bank = true)
        assertEquals("Gesynchroniseerd: 0 nieuw in de receptenbank, 2 bijgewerkt, 1 verwijderd", outcome.message)
        assertEquals(0, outcome.newInBank)
    }

    @Test
    fun againstAServerFromBeforeTheBankTheSyncStillSaysAdded() = runTest {
        val outcome = syncCookingSite(api(mutableListOf()), Strings.English, bank = false)
        assertEquals("Synced: 3 added, 1 updated, 0 removed", outcome.message)
        assertEquals(0, outcome.newInBank)
    }
}
