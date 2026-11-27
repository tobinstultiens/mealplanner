package dev.tobins.mealplanner.app.recipes

import dev.tobins.mealplanner.api.RecipeKind
import dev.tobins.mealplanner.api.RecipeSource
import dev.tobins.mealplanner.api.RecipeSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipePicturesTest {
    @Test
    fun theServersRelativeAddressIsMadeFull() {
        assertEquals(
            "https://meals.example.nl/api/recipes/4/image?v=ab",
            recipePictureAddress("https://meals.example.nl", "/api/recipes/4/image?v=ab"),
        )
        assertEquals(
            "https://meals.example.nl/api/recipes/4/image?v=ab",
            recipePictureAddress("https://meals.example.nl/", "/api/recipes/4/image?v=ab"),
        )
    }

    @Test
    fun noPictureMeansNoAddress() {
        assertNull(recipePictureAddress("https://meals.example.nl", null))
    }

    @Test
    fun anAddressOfAnotherHostIsNeverUsed() {
        // The login token goes along with every request, so it must only ever reach our own server.
        assertNull(recipePictureAddress("https://meals.example.nl", "https://elsewhere.example/p.jpg"))
        assertNull(recipePictureAddress("https://meals.example.nl", "//elsewhere.example/p.jpg"))
    }

    @Test
    fun onlyRecipesWithAPhotoAreListedById() {
        val recipes = listOf(summary(1, "/api/recipes/1/image?v=a"), summary(2, null), summary(3, "/api/recipes/3/image?v=b"))
        assertEquals(mapOf(1L to "/api/recipes/1/image?v=a", 3L to "/api/recipes/3/image?v=b"), pictureUrls(recipes))
    }

    private fun summary(id: Long, imageUrl: String?) = RecipeSummary(
        id = id, title = "Recipe $id", section = "Dinner", tags = emptyList(), servings = 2,
        source = RecipeSource.COOKING_SITE, kind = RecipeKind.MEAL, plannable = true, nutrition = null,
        mealSlots = emptyList(), imageUrl = imageUrl,
    )
}
