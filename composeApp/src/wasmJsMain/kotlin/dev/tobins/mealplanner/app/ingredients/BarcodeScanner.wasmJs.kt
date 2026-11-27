package dev.tobins.mealplanner.app.ingredients

import androidx.compose.runtime.Composable

/** The browser has no scanner: the barcode is typed. */
@Composable
actual fun rememberBarcodeScanner(): BarcodeScanner? = null
