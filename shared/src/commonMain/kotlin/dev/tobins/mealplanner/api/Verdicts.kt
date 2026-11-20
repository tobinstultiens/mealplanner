package dev.tobins.mealplanner.api

import kotlinx.serialization.Serializable

/**
 * Whether an offer is a good deal, compared with its own pack's offers at its store over time (issue 118;
 * docs/data-sources.md, "Is it a good deal?"). The values are the server's verdicts, in the order it tries them.
 */
enum class VerdictKind {
    /** At or below the lowest of its pack seen, and clearly below at least half of its earlier offers. */
    REAL_DEAL,

    /** About what its pack usually goes for on offer. */
    USUAL_DEAL,

    /** Clearly dearer than most of its pack's earlier offers. */
    WORSE_THAN_USUAL,

    /** On offer this same way on nearly every day the store was read: its usual price, not a deal. */
    ALWAYS_ON_OFFER,

    /** Its regular price went up just before, and with the deal it costs about what it cost before. */
    NOT_A_DEAL,

    /** Another offer of the same pack, which the store shows today too, is clearly cheaper. */
    CHEAPER_ANOTHER_WAY,

    /** The deal gives nothing to compare by. */
    NOT_COMPARABLE,

    /** Too little history to say. */
    NOT_ENOUGH_HISTORY,
}

/** What a verdict compares by: euros per kg, litre, piece or pack, or the share of the regular price paid. */
enum class VerdictMeasure { PER_KG, PER_LITRE, PER_PIECE, PER_PACK, PAID_SHARE }

/**
 * The verdict on one offer ([Offer.verdict]), with the facts the app words it from. Filled in by the server for the
 * calling account's offers that are valid today at its stores and match one of its ingredients; computed when the
 * answer is made, so a list kept offline keeps the verdict it had. Every field has a default, so that a later
 * server may leave out what it no longer has, and an enum value this app does not know reads as the default.
 */
@Serializable
data class PriceVerdict(
    val verdict: VerdictKind = VerdictKind.NOT_ENOUGH_HISTORY,
    /** What [value], [typical] and the offers' values are in; null when nothing was compared. */
    val measure: VerdictMeasure? = null,
    /** This offer in [measure]. */
    val value: Double? = null,
    /** What its earlier offers usually came to (the median), in [measure]. */
    val typical: Double? = null,
    /** A usual deal clearly below the usual, but not the lowest: "better than usual". */
    val betterThanUsual: Boolean = false,
    /** The latest earlier offer at least as cheap as this one; null when none was (a real deal is then the lowest so far). */
    val lastAsCheap: PastOffer? = null,
    /** Days the store was read in the last year, and the days a verdict needs. */
    val daysRead: Int = 0,
    val daysNeeded: Int = 0,
    /** The product came in other packs too: the verdict is on this pack alone. */
    val thisPack: Boolean = false,
    /** The regular price went up shortly before the offer began, from [PriceRaise.before] to [PriceRaise.now] a pack. */
    val raise: PriceRaise? = null,
    /** The offer of the same pack that is clearly cheaper now ([VerdictKind.CHEAPER_ANOTHER_WAY]). */
    val cheaperNow: PastOffer? = null,
    /** An offer of another pack clearly cheaper per kg or per litre, shown now or ended. */
    val cheaperPack: CheaperPack? = null,
    /** The other offers of the product the store shows today. */
    val alongside: List<PastOffer> = emptyList(),
    /** The latest earlier offers of this pack, newest first: a few, for the detail. */
    val earlier: List<PastOffer> = emptyList(),
)

/**
 * Another offer of the product: its store dates (ISO), its deal text or, without one, its price, a short name of its
 * pack where it gives a size ("1,5 l", "24 × 250 ml"), and its value in the verdict's measure. [endedEarly]: the store
 * stopped showing it before its dates ended.
 */
@Serializable
data class PastOffer(
    val from: String,
    val until: String,
    val deal: String? = null,
    val price: Double? = null,
    val packName: String? = null,
    val value: Double? = null,
    val endedEarly: Boolean = false,
)

/**
 * Another pack's [offer], clearly cheaper in [measure] (per kg or per litre; null for a measure this app does not
 * know); [PastOffer.value] is in that measure. Shown [now], or ended.
 */
@Serializable
data class CheaperPack(val offer: PastOffer, val measure: VerdictMeasure? = null, val now: Boolean = false)

/** A regular price raised from [before] to [now], a pack. */
@Serializable
data class PriceRaise(val before: Double, val now: Double)
