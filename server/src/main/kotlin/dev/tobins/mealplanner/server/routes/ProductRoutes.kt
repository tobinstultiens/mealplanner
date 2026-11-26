package dev.tobins.mealplanner.server.routes

import dev.tobins.mealplanner.server.products.ProductFinder
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Looking a product's label up (issue 354): suggestions for the product's form from AH and Open Food Facts, which save
 * nothing and read nothing of the account. Behind the login all the same, so that only accounts make the server ask.
 */
fun Route.productRoutes(finder: ProductFinder) {
    get("/api/products/search") { call.respond(finder.search(call.request.queryParameters["q"])) }

    get("/api/products/ah/{id}") { call.respond(finder.ahProduct(call.parameters["id"], call.request.queryParameters["packOf"])) }

    get("/api/products/barcode/{code}") { call.respond(finder.barcode(call.parameters["code"])) }
}
