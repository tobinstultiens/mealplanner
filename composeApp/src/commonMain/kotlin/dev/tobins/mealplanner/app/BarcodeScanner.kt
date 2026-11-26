package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable

/** What a scan came to (issue 356). */
sealed interface ScanResult {
    /** The [digits] of a barcode (EAN-13, EAN-8 or UPC-A, check digit right), the first one the camera saw. */
    data class Read(val digits: String) : ScanResult

    /** The camera's permission was refused. */
    data object Refused : ScanResult

    /** The camera could not be opened. */
    data object Unavailable : ScanResult
}

/**
 * Reads a barcode with the camera, for the product form's lookup. Only the digits leave the scanner: no picture is kept.
 * Closing the scanner without a barcode gives no result.
 */
interface BarcodeScanner {
    /**
     * Where what a scan came to goes: set by the screen while it is shown, so that a scan still open after the phone was
     * turned (the screen is made again) reaches the new screen.
     */
    var onResult: ((ScanResult) -> Unit)?

    /** Asks for the camera's permission when it is first needed, opens the scanner and gives [onResult] what it came to. */
    fun scan()

    /** Opens this app's page in the system's settings, where the camera can be allowed after a refusal. */
    fun openSettings()
}

/**
 * The scanner of this platform, or null where there is none (the browser, a phone without a camera): the form then has
 * no scan button and the barcode is typed.
 */
@Composable
expect fun rememberBarcodeScanner(): BarcodeScanner?
