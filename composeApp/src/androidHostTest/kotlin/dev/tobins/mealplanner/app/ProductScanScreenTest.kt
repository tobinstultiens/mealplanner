package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The product form's scan button (issue 356), with a scanner that answers as the camera would: a barcode read is looked up
 * as a typed one is, a refused permission is said and typing stays possible, and without a scanner (the browser) there is no button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProductScanScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val backend = FakeBackend(writing = true)
    private val api = ApiClient(backend.settings)

    /** Answers each scan with the next of [answers]; counts them. */
    private class FakeScanner(vararg answers: ScanResult) : BarcodeScanner {
        private val left = answers.toMutableList()
        var scans = 0

        override fun scan(onResult: (ScanResult) -> Unit) {
            scans++
            onResult(left.removeAt(0))
        }
    }

    @After
    fun tearDown() {
        api.close()
        backend.close()
    }

    private fun show(scanner: BarcodeScanner?) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides Strings.English) { ProductScreen(api, null, {}, {}, scanner = scanner) }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun field(label: String) = compose.onAllNodes(hasText(label) and hasSetTextAction())[0]

    private fun shown(label: String): String = field(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test
    fun aScannedBarcodeFillsTheLookupAndThenTheFormAsATypedOneDoes() {
        val scanner = FakeScanner(ScanResult.Read("8712800035626"))
        show(scanner)
        waitFor("Values per")
        tap("Scan barcode")
        waitFor("Values from AH")

        assertEquals("8712800035626", shown("Name or barcode"))
        assertEquals("Optimel Drinkyoghurt framboos", shown("Name"))
        assertTrue(backend.requests.contains("GET /api/products/barcode/8712800035626"))
        assertTrue(backend.requests.none { it.startsWith("GET /api/products/search") })
        assertEquals(1, scanner.scans)
    }

    @Test
    fun aRefusedPermissionIsSaidAndATypedBarcodeStillWorks() {
        val scanner = FakeScanner(ScanResult.Refused, ScanResult.Read("8712800035626"))
        show(scanner)
        waitFor("Values per")
        tap("Scan barcode")
        waitFor("The camera is not allowed")
        compose.onNodeWithText("Type the digits instead, or allow the camera", substring = true).assertExists()
        assertTrue(backend.requests.none { it.startsWith("GET /api/products/barcode") })

        field("Name or barcode").performScrollTo().performTextReplacement("8712800035626")
        // The line was about the scan before it: typing says it is no longer true.
        compose.onNodeWithText("The camera is not allowed", substring = true).assertDoesNotExist()
        tap("Look up")
        waitFor("Values from AH")
        assertEquals("Optimel Drinkyoghurt framboos", shown("Name"))
    }

    @Test
    fun theScanButtonAsksAgainAfterARefusal() {
        val scanner = FakeScanner(ScanResult.Refused, ScanResult.Read("8712800035626"))
        show(scanner)
        waitFor("Values per")
        tap("Scan barcode")
        waitFor("The camera is not allowed")
        tap("Scan barcode")
        waitFor("Values from AH")
        compose.onNodeWithText("The camera is not allowed", substring = true).assertDoesNotExist()
        assertEquals(2, scanner.scans)
    }

    @Test
    fun aCameraThatCannotBeOpenedIsSaid() {
        show(FakeScanner(ScanResult.Unavailable))
        waitFor("Values per")
        tap("Scan barcode")
        waitFor("The camera could not be opened")
    }

    @Test
    fun withoutAScannerThereIsNoScanButton() {
        show(null)
        waitFor("Values per")
        compose.onNodeWithText("Look up").assertExists()
        compose.onNodeWithText("Scan barcode").assertDoesNotExist()
    }
}
