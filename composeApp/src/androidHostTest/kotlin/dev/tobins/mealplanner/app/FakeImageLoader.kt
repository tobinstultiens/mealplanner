package dev.tobins.mealplanner.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult

/**
 * A picture loader that never reaches the network: it answers every request itself, with a
 * drawn "product" (a coloured pack with a lid), or with a failure for [failing] addresses so the
 * screenshots also show what a row looks like when the picture does not load.
 */
fun fakeImageLoader(context: Context, failing: (String) -> Boolean = { false }): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    val request = chain.request
                    if (failing(request.data.toString())) return ErrorResult(null, request, IllegalStateException("not found"))
                    return SuccessResult(pack(request.data.toString()).asImage(), request, DataSource.MEMORY)
                }
            })
        }
        .build()

/** The same drawing for the same address, in a colour taken from it. */
private fun pack(url: String): Bitmap {
    val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val colours = intArrayOf(0xFFC0392B.toInt(), 0xFF2E86C1.toInt(), 0xFF27AE60.toInt(), 0xFFD4AC0D.toInt())
    paint.color = colours[(url.hashCode() and 0x7fffffff) % colours.size]
    canvas.drawRoundRect(50f, 50f, 150f, 180f, 12f, 12f, paint)
    paint.color = Color.DKGRAY
    canvas.drawRect(60f, 30f, 140f, 50f, paint)
    return bitmap
}
