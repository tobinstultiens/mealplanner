package dev.tobins.mealplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's own artwork, as vectors, in the same visual language as the launcher icon: round
 * shapes, the plate, and one amber accent for "today" or "look here". The launcher icon, the
 * splash screen and the favicon draw the same mark from the files in `androidApp/src/main/res`
 * and `composeApp/src/wasmJsMain/resources`; this is the copy the app itself draws.
 */
object Illustrations {
    private val Cream = Color(0xFFF7FBF1)
    private val Green = Color(0xFF2E7D32)

    /** Amber of "today" and of the accents of the spot illustrations: the same on light and dark. */
    internal val Amber = Color(0xFFF9A825)

    /** The days of the week as the plate's rim, from the top, clockwise. */
    private val Days = listOf(
        "M46.28,35.55A20,20 0 0 1 61.72,35.55L59.21,41.55A13.5,13.5 0 0 0 48.79,41.55Z",
        "M63.61,36.46A20,20 0 0 1 73.24,48.54L66.99,50.31A13.5,13.5 0 0 0 60.49,42.16Z",
        "M73.7,50.58A20,20 0 0 1 70.27,65.63L64.98,61.85A13.5,13.5 0 0 0 67.3,51.69Z",
        "M68.96,67.27A20,20 0 0 1 55.05,73.97L54.71,67.48A13.5,13.5 0 0 0 64.1,62.96Z",
        "M52.95,73.97A20,20 0 0 1 39.04,67.27L43.9,62.96A13.5,13.5 0 0 0 53.29,67.48Z",
        "M37.73,65.63A20,20 0 0 1 34.3,50.58L40.7,51.69A13.5,13.5 0 0 0 43.02,61.85Z",
        "M34.76,48.54A20,20 0 0 1 44.39,36.46L47.51,42.16A13.5,13.5 0 0 0 41.01,50.31Z",
    )
    private const val WELL_AND_TICK = "M44.5,54a9.5,9.5 0 1 0 19,0a9.5,9.5 0 1 0 -19,0Z M48.8,54.3L52.7,58.4L59.6,49.9L57.3,48L52.5,53.9L50.6,52.3Z"
    private const val FORK = "M23.2,33h0a1.2,1.2 0 0 1 1.2,1.2v18.6a1.2,1.2 0 0 1 -1.2,1.2h0a1.2,1.2 0 0 1 -1.2,-1.2v-18.6a1.2,1.2 0 0 1 1.2,-1.2Z M26.7,33h0a1.2,1.2 0 0 1 1.2,1.2v18.6a1.2,1.2 0 0 1 -1.2,1.2h0a1.2,1.2 0 0 1 -1.2,-1.2v-18.6a1.2,1.2 0 0 1 1.2,-1.2Z M30.2,33h0a1.2,1.2 0 0 1 1.2,1.2v18.6a1.2,1.2 0 0 1 -1.2,1.2h0a1.2,1.2 0 0 1 -1.2,-1.2v-18.6a1.2,1.2 0 0 1 1.2,-1.2Z M25.2,45.6h3a3.2,3.2 0 0 1 3.2,3.2v5a3.2,3.2 0 0 1 -3.2,3.2h-3a3.2,3.2 0 0 1 -3.2,-3.2v-5a3.2,3.2 0 0 1 3.2,-3.2Z M26.7,51.9h0a1.3,1.3 0 0 1 1.3,1.3v20.5a1.3,1.3 0 0 1 -1.3,1.3h0a1.3,1.3 0 0 1 -1.3,-1.3v-20.5a1.3,1.3 0 0 1 1.3,-1.3Z"
    private const val KNIFE = "M78.5,33Q85,33 85,40.5V73.55a1.45,1.45 0 0 1 -2.9,0V56Q82.1,55 78.5,51Z"

    /**
     * The full mark on its green tile, for the logged-out screen: a plate between fork and knife,
     * its rim in seven segments for the days (today's amber) and a tick in the well. It keeps its own
     * colours, since it is a logo, not an icon that takes the theme's.
     */
    val Mark: ImageVector by lazy {
        val builder = ImageVector.Builder("Mark", 96.dp, 96.dp, 108f, 108f)
            .addPath(
                pathData = addPathNodes("M24,0H84A24,24 0 0 1 108,24V84A24,24 0 0 1 84,108H24A24,24 0 0 1 0,84V24A24,24 0 0 1 24,0Z"),
                fill = SolidColor(Green),
            )
        Days.forEachIndexed { day, path ->
            builder.addPath(pathData = addPathNodes(path), fill = SolidColor(if (day == TODAY) Amber else Cream))
        }
        // The tick is a hole in the well, so the green shows through.
        builder.addPath(pathData = addPathNodes(WELL_AND_TICK), fill = SolidColor(Cream), pathFillType = PathFillType.EvenOdd)
        builder.addPath(pathData = addPathNodes(FORK), fill = SolidColor(Cream))
        builder.addPath(pathData = addPathNodes(KNIFE), fill = SolidColor(Cream))
        builder.build()
    }

    /** Which of the days is today's in the artwork: the mark is not a clock, so it is a fixed one. */
    private const val TODAY = 1

    /** [spot] drawn in the colours of the current theme. */
    @Composable
    fun spot(spot: Spot): ImageVector {
        val ink = MaterialTheme.colorScheme.primary
        val soft = MaterialTheme.colorScheme.primaryContainer
        return remember(spot, ink, soft) { spot.build(ink, soft) }
    }
}

/** The parts of a spot illustration are drawn in these; the theme gives [Ink] and [Soft]. */
internal enum class SpotColor { Ink, Soft, Amber }

internal class Layer(val color: SpotColor, val path: String)

/** The spot illustrations of the empty states, drawn in a 96 × 96 square. */
enum class Spot private constructor(private vararg val layers: Layer) {
    /** Nothing to cook: a plate with a plus in the well, laid with fork and knife. */
    NoRecipes(
            Layer(SpotColor.Soft, "M23,48a25,25 0 1 0 50,0a25,25 0 1 0 -50,0Z"),
            Layer(SpotColor.Ink, "M31,48a17,17 0 1 0 34,0a17,17 0 1 0 -34,0ZM32.6,48a15.4,15.4 0 1 1 30.8,0a15.4,15.4 0 1 1 -30.8,0Z"),
            Layer(SpotColor.Amber, "M48,41.5h0a1.7,1.7 0 0 1 1.7,1.7v9.6a1.7,1.7 0 0 1 -1.7,1.7h0a1.7,1.7 0 0 1 -1.7,-1.7v-9.6a1.7,1.7 0 0 1 1.7,-1.7Z M43.2,46.3h9.6a1.7,1.7 0 0 1 1.7,1.7v0a1.7,1.7 0 0 1 -1.7,1.7h-9.6a1.7,1.7 0 0 1 -1.7,-1.7v0a1.7,1.7 0 0 1 1.7,-1.7Z"),
            Layer(SpotColor.Ink, "M11.3,24h0a1.3,1.3 0 0 1 1.3,1.3v21.4a1.3,1.3 0 0 1 -1.3,1.3h0a1.3,1.3 0 0 1 -1.3,-1.3v-21.4a1.3,1.3 0 0 1 1.3,-1.3Z M15.5,24h0a1.3,1.3 0 0 1 1.3,1.3v21.4a1.3,1.3 0 0 1 -1.3,1.3h0a1.3,1.3 0 0 1 -1.3,-1.3v-21.4a1.3,1.3 0 0 1 1.3,-1.3Z M19.7,24h0a1.3,1.3 0 0 1 1.3,1.3v21.4a1.3,1.3 0 0 1 -1.3,1.3h0a1.3,1.3 0 0 1 -1.3,-1.3v-21.4a1.3,1.3 0 0 1 1.3,-1.3Z M13.2,38.4h4.6a3.2,3.2 0 0 1 3.2,3.2v6.2a3.2,3.2 0 0 1 -3.2,3.2h-4.6a3.2,3.2 0 0 1 -3.2,-3.2v-6.2a3.2,3.2 0 0 1 3.2,-3.2Z M15.5,45.6h0a1.5,1.5 0 0 1 1.5,1.5v23.4a1.5,1.5 0 0 1 -1.5,1.5h0a1.5,1.5 0 0 1 -1.5,-1.5v-23.4a1.5,1.5 0 0 1 1.5,-1.5Z"),
            Layer(SpotColor.Ink, "M77,24Q84,24 84,32V70.4a1.6,1.6 0 0 1 -3.2,0V50Q80.8,49 77,45Z"),
    ),
    /** Nothing planned: the week's seven days around an empty plate, today in amber. */
    NothingPlanned(
            Layer(SpotColor.Soft, "M37.04,21.15A29,29 0 0 1 58.96,21.15L55.56,29.48A20,20 0 0 0 40.44,29.48Z"),
            Layer(SpotColor.Amber, "M62.15,22.69A29,29 0 0 1 75.83,39.83L67.19,42.37A20,20 0 0 0 57.76,30.54Z"),
            Layer(SpotColor.Soft, "M76.61,43.28A29,29 0 0 1 71.73,64.66L64.37,59.49A20,20 0 0 0 67.73,44.75Z"),
            Layer(SpotColor.Soft, "M69.53,67.43A29,29 0 0 1 49.77,76.95L49.22,67.96A20,20 0 0 0 62.85,61.4Z"),
            Layer(SpotColor.Soft, "M46.23,76.95A29,29 0 0 1 26.47,67.43L33.15,61.4A20,20 0 0 0 46.78,67.96Z"),
            Layer(SpotColor.Soft, "M24.27,64.66A29,29 0 0 1 19.39,43.28L28.27,44.75A20,20 0 0 0 31.63,59.49Z"),
            Layer(SpotColor.Soft, "M20.17,39.83A29,29 0 0 1 33.85,22.69L38.24,30.54A20,20 0 0 0 28.81,42.37Z"),
            Layer(SpotColor.Ink, "M34,48a14,14 0 1 0 28,0a14,14 0 1 0 -28,0ZM35.6,48a12.4,12.4 0 1 1 24.8,0a12.4,12.4 0 1 1 -24.8,0Z"),
    ),
    /** Nothing to buy: a shopping basket with a tick. */
    NothingToBuy(
            Layer(SpotColor.Soft, "M19,44H77L70,74Q69,79 64,79H32Q27,79 26,74Z"),
            Layer(SpotColor.Ink, "M26,38A22,22 0 0 1 70,38L66.5,38A18.5,18.5 0 0 0 29.5,38Z"),
            Layer(SpotColor.Ink, "M17,38h62a4,4 0 0 1 4,4v0a4,4 0 0 1 -4,4h-62a4,4 0 0 1 -4,-4v0a4,4 0 0 1 4,-4Z"),
            Layer(SpotColor.Ink, "M37.08,62.68L45.27,71.29L59.76,53.44L54.93,49.45L44.85,61.84L40.86,58.48Z"),
    ),
    /** No offers: a plate with a price tag laid on its rim. */
    NoOffers(
            Layer(SpotColor.Soft, "M18,54a26,26 0 1 0 52,0a26,26 0 1 0 -52,0Z"),
            Layer(SpotColor.Ink, "M27,54a17,17 0 1 0 34,0a17,17 0 1 0 -34,0ZM28.6,54a15.4,15.4 0 1 1 30.8,0a15.4,15.4 0 1 1 -30.8,0Z"),
            Layer(SpotColor.Amber, "M51.62,56.53L64.15,54.32L86.27,69.8L75.94,84.55L53.83,69.06Z M57.21,62.26a2.6,2.6 0 1 0 5.2,0a2.6,2.6 0 1 0 -5.2,0Z"),
    );

    internal fun build(ink: Color, soft: Color): ImageVector {
        val builder = ImageVector.Builder(name, 96.dp, 96.dp, 96f, 96f)
        layers.forEach { layer ->
            val color = when (layer.color) {
                SpotColor.Ink -> ink
                SpotColor.Soft -> soft
                SpotColor.Amber -> Illustrations.Amber
            }
            builder.addPath(pathData = addPathNodes(layer.path), fill = SolidColor(color))
        }
        return builder.build()
    }
}
