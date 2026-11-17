package dev.tobins.mealplanner.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.tobins.mealplanner.api.RecipeSummary

/**
 * How to fetch recipe photos: they come from our own server, behind the login, so a request carries
 * the account's access token. [accessToken] is asked for every request, because the token is renewed
 * while the app runs. Null (the default of [LocalRecipeImages]) means no photos are loaded: the
 * screenshot tests provide a fake loader instead, as they must not reach the network.
 *
 * It uses the same [loader] as the product pictures, and is not governed by their setting: the
 * photos are not the stores'.
 */
class RecipeImages(val loader: ImageLoader, val serverUrl: String, val accessToken: () -> String?)

val LocalRecipeImages = staticCompositionLocalOf<RecipeImages?> { null }

/**
 * The full address of a recipe's photo, or null when the recipe has none. The server gives the
 * address relative to itself; only such addresses are accepted, so the token cannot be sent to
 * another host.
 */
fun recipePictureAddress(serverUrl: String, imageUrl: String?): String? {
    if (imageUrl == null || !imageUrl.startsWith("/") || imageUrl.startsWith("//")) return null
    return serverUrl.trimEnd('/') + imageUrl
}

/** The photo of each recipe that has one, by recipe id: the plan and the day only know the id. */
fun pictureUrls(recipes: List<RecipeSummary>): Map<Long, String> =
    recipes.mapNotNull { recipe -> recipe.imageUrl?.let { recipe.id to it } }.toMap()

/**
 * My recipes, loaded once for a screen that only has recipe ids (Today, Plan), for their photos and for
 * what the screen says of the rotation. Null until they arrive and when they cannot be loaded: it is an
 * extra, the screen works without.
 */
@Composable
fun rememberMyRecipes(api: ApiClient): State<List<RecipeSummary>?> {
    val recipes = remember(api) { mutableStateOf<List<RecipeSummary>?>(null) }
    LaunchedEffect(api) { runCatching { api.recipes() }.onSuccess { recipes.value = it } }
    return recipes
}

/** The photos of my recipes (see [rememberMyRecipes]); empty until they arrive and when they cannot be loaded. */
@Composable
fun rememberRecipePictureUrls(api: ApiClient): State<Map<Long, String>> {
    val recipes = rememberMyRecipes(api)
    return remember { derivedStateOf { recipes.value?.let(::pictureUrls).orEmpty() } }
}

/** What [RecipePhoto] draws; only exists for a recipe that has a photo and when photos can be loaded. */
class RecipePicture internal constructor(internal val address: String, internal val images: RecipeImages)

/**
 * The photo to show for [imageUrl], or null. Callers build two looks from this: with a photo, the
 * photo; without one, exactly the layout there was before, with no box, placeholder or space kept.
 */
@Composable
fun recipePicture(imageUrl: String?): RecipePicture? {
    val images = LocalRecipeImages.current ?: return null
    return recipePictureAddress(images.serverUrl, imageUrl)?.let { RecipePicture(it, images) }
}

/**
 * The photo, cropped to fill [modifier]'s size with rounded corners. Decorative: the title beside it
 * names the recipe. While it loads, or when it fails, the area shows the theme's surface colour.
 */
@Composable
fun RecipePhoto(picture: RecipePicture, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val token = picture.images.accessToken()
    val request = remember(picture.address, token, context) {
        ImageRequest.Builder(context)
            .data(picture.address)
            .apply { token?.let { httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $it").build()) } }
            .build()
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = picture.images.loader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
