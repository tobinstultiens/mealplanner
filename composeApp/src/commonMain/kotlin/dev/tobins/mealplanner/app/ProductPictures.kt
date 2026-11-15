package dev.tobins.mealplanner.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage

/**
 * Loads the product pictures. Null (the default) means none are loaded and every row keeps its
 * placeholder: when the user switched pictures off, and in the screenshot tests, which must not
 * reach the network.
 */
val LocalProductImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** Hosts that send no CORS headers, so a browser cannot hand their pictures to the page (docs/data-sources.md). */
private val hostsWithoutCors = setOf("www.jumbo.com")

/**
 * The address to load [url] from on this platform, or null when only the placeholder will do:
 * there is no picture, or the app runs in a [browser] and the picture's host does not allow that.
 */
fun loadablePictureUrl(url: String?, browser: Boolean): String? {
    if (url.isNullOrBlank()) return null
    val host = url.substringAfter("://").substringBefore('/').lowercase()
    return url.takeUnless { browser && host in hostsWithoutCors }
}

/** The first letter or digit of [name], capitalised: what stands in for a picture. */
fun pictureInitial(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"

/**
 * A product's picture in a rounded square of fixed [size], so rows keep their height while it
 * loads, when it fails and when there is none: the square then shows the [name]'s first letter.
 * The picture is decorative, the row beside it names the product. With [dimmed] it is faded,
 * like the rest of a ticked-off row.
 */
@Composable
fun ProductPicture(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 48.dp, dimmed: Boolean = false) {
    val loader = LocalProductImageLoader.current
    val address = if (loader == null) null else loadablePictureUrl(url, runsInBrowser())
    var loaded by remember(address) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).alpha(if (dimmed) 0.5f else 1f)
            // Product shots come on white; the placeholder uses the theme's own surface.
            .background(if (loaded) Color.White else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(pictureInitial(name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (address != null && loader != null) {
            AsyncImage(
                model = address,
                contentDescription = null,
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
