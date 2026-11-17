package dev.tobins.mealplanner.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import dev.tobins.mealplanner.shopping.ShopSection

/**
 * The few icons the app uses, as our own vectors: an icon library would add thousands of
 * unused ones to the web build, which is already short on memory when compiling.
 *
 * The shapes are Google's Material Symbols (outlined style, weight 400, 24 px; the
 * "Filled" ones are the same symbol with fill 1), copied unchanged from
 * https://github.com/google/material-design-icons (`symbols/web/<name>/materialsymbolsoutlined`)
 * and licensed under the Apache License 2.0. Each is named after what it means in this app;
 * its KDoc gives the symbol's own name, for finding it again.
 *
 * Icons that point in the reading direction (back, the chevrons, the open book) mirror
 * themselves in right-to-left layouts.
 */
object AppIcons {
    /** Material Symbol `arrow_back`. */
    val Back: ImageVector by lazy {
        symbol(
            "Back",
            "m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z",
            autoMirror = true,
        )
    }

    /** Material Symbol `chevron_left`. */
    val ChevronLeft: ImageVector by lazy {
        symbol(
            "ChevronLeft",
            "M560-240 320-480l240-240 56 56-184 184 184 184-56 56Z",
            autoMirror = true,
        )
    }

    /** Material Symbol `chevron_right`. */
    val ChevronRight: ImageVector by lazy {
        symbol(
            "ChevronRight",
            "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z",
            autoMirror = true,
        )
    }

    /** Material Symbol `more_vert`. */
    val More: ImageVector by lazy {
        symbol(
            "More",
            "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm0-240q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Z",
        )
    }

    /** Material Symbol `close`. */
    val Close: ImageVector by lazy {
        symbol(
            "Close",
            "m256-200-56-56 224-224-224-224 56-56 224 224 224-224 56 56-224 224 224 224-56 56-224-224-224 224Z",
        )
    }

    /** Material Symbol `add`. */
    val Add: ImageVector by lazy {
        symbol(
            "Add",
            "M440-440H200v-80h240v-240h80v240h240v80H520v240h-80v-240Z",
        )
    }

    /** Material Symbol `check`. */
    val Check: ImageVector by lazy {
        symbol(
            "Check",
            "M382-240 154-468l57-57 171 171 367-367 57 57-424 424Z",
        )
    }

    /** Material Symbol `sync`. */
    val Sync: ImageVector by lazy {
        symbol(
            "Sync",
            "M160-160v-80h110l-16-14q-52-46-73-105t-21-119q0-111 66.5-197.5T400-790v84q-72 26-116 88.5T240-478q0 45 17 87.5t53 78.5l10 10v-98h80v240H160Zm400-10v-84q72-26 116-88.5T720-482q0-45-17-87.5T650-648l-10-10v98h-80v-240h240v80H690l16 14q49 49 71.5 106.5T800-482q0 111-66.5 197.5T560-170Z",
        )
    }

    /** Material Symbol `refresh`. */
    val Refresh: ImageVector by lazy {
        symbol(
            "Refresh",
            "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z",
        )
    }

    /** Material Symbol `edit`. */
    val Edit: ImageVector by lazy {
        symbol(
            "Edit",
            "M200-200h57l391-391-57-57-391 391v57Zm-80 80v-170l528-527q12-11 26.5-17t30.5-6q16 0 31 6t26 18l55 56q12 11 17.5 26t5.5 30q0 16-5.5 30.5T817-647L290-120H120Zm640-584-56-56 56 56Zm-141 85-28-29 57 57-29-28Z",
        )
    }

    /** Material Symbol `person`. */
    val Person: ImageVector by lazy {
        symbol(
            "Person",
            "M480-480q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47ZM160-160v-112q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v112H160Zm80-80h480v-32q0-11-5.5-20T700-306q-54-27-109-40.5T480-360q-56 0-111 13.5T260-306q-9 5-14.5 14t-5.5 20v32Zm240-320q33 0 56.5-23.5T560-640q0-33-23.5-56.5T480-720q-33 0-56.5 23.5T400-640q0 33 23.5 56.5T480-560Zm0-80Zm0 400Z",
        )
    }

    /** Material Symbol `settings`. */
    val Settings: ImageVector by lazy {
        symbol(
            "Settings",
            "m370-80-16-128q-13-5-24.5-12T307-235l-119 50L78-375l103-78q-1-7-1-13.5v-27q0-6.5 1-13.5L78-585l110-190 119 50q11-8 23-15t24-12l16-128h220l16 128q13 5 24.5 12t22.5 15l119-50 110 190-103 78q1 7 1 13.5v27q0 6.5-2 13.5l103 78-110 190-118-50q-11 8-23 15t-24 12L590-80H370Zm70-80h79l14-106q31-8 57.5-23.5T639-327l99 41 39-68-86-65q5-14 7-29.5t2-31.5q0-16-2-31.5t-7-29.5l86-65-39-68-99 42q-22-23-48.5-38.5T533-694l-13-106h-79l-14 106q-31 8-57.5 23.5T321-633l-99-41-39 68 86 64q-5 15-7 30t-2 32q0 16 2 31t7 30l-86 65 39 68 99-42q22 23 48.5 38.5T427-266l13 106Zm42-180q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Zm-2-140Z",
        )
    }

    /** Material Symbol `auto_awesome`. */
    val Generate: ImageVector by lazy {
        symbol(
            "Generate",
            "m19 9-1.25-2.75L15 5l2.75-1.25L19 1l1.25 2.75L23 5l-2.75 1.25Zm0 14-1.25-2.75L15 19l2.75-1.25L19 15l1.25 2.75L23 19l-2.75 1.25ZM9 20l-2.5-5.5L1 12l5.5-2.5L9 4l2.5 5.5L17 12l-5.5 2.5Zm0-4.85L10 13l2.15-1L10 11 9 8.85 8 11l-2.15 1L8 13ZM9 12Z",
            viewport = 24f,
        )
    }

    /** Material Symbol `link`. */
    val Link: ImageVector by lazy {
        symbol(
            "Link",
            "M440-280H280q-83 0-141.5-58.5T80-480q0-83 58.5-141.5T280-680h160v80H280q-50 0-85 35t-35 85q0 50 35 85t85 35h160v80ZM320-440v-80h320v80H320Zm200 160v-80h160q50 0 85-35t35-85q0-50-35-85t-85-35H520v-80h160q83 0 141.5 58.5T880-480q0 83-58.5 141.5T680-280H520Z",
        )
    }

    /** Material Symbol `sell`. */
    val Offer: ImageVector by lazy {
        symbol(
            "Offer",
            "M856-390 570-104q-12 12-27 18t-30 6q-15 0-30-6t-27-18L103-457q-11-11-17-25.5T80-513v-287q0-33 23.5-56.5T160-880h287q16 0 31 6.5t26 17.5l352 353q12 12 17.5 27t5.5 30q0 15-5.5 29.5T856-390ZM513-160l286-286-353-354H160v286l353 354ZM260-640q25 0 42.5-17.5T320-700q0-25-17.5-42.5T260-760q-25 0-42.5 17.5T200-700q0 25 17.5 42.5T260-640Zm220 160Z",
        )
    }

    /** Material Symbol `kitchen`. */
    val Pantry: ImageVector by lazy {
        symbol(
            "Pantry",
            "M320-640v-120h80v120h-80Zm0 360v-200h80v200h-80ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h480q33 0 56.5 23.5T800-800v640q0 33-23.5 56.5T720-80H240Zm0-80h480v-360H240v360Zm0-440h480v-200H240v200Z",
        )
    }

    /** Material Symbol `euro_symbol`. */
    val Price: ImageVector by lazy {
        symbol(
            "Price",
            "M600-120q-118 0-210-67T260-360H120v-80h122q-2-11-2-20v-40q0-9 2-20H120v-80h140q38-106 130-173t210-67q69 0 130.5 24T840-748l-70 70q-35-29-78.5-45.5T600-740q-75 0-136.5 38.5T370-600h230v80H344q-2 11-3 20t-1 20q0 11 1 20t3 20h256v80H370q32 63 93.5 101.5T600-220q48 0 92.5-16.5T770-282l70 70q-48 44-109.5 68T600-120Z",
        )
    }

    /** Material Symbol `today`. */
    val Today: ImageVector by lazy {
        symbol(
            "Today",
            "M360-300q-42 0-71-29t-29-71q0-42 29-71t71-29q42 0 71 29t29 71q0 42-29 71t-71 29ZM200-80q-33 0-56.5-23.5T120-160v-560q0-33 23.5-56.5T200-800h40v-80h80v80h320v-80h80v80h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Zm0-480h560v-80H200v80Zm0 0v-80 80Z",
        )
    }

    /** Material Symbol `today (filled)`. */
    val TodayFilled: ImageVector by lazy {
        symbol(
            "TodayFilled",
            "M360-300q-42 0-71-29t-29-71q0-42 29-71t71-29q42 0 71 29t29 71q0 42-29 71t-71 29ZM200-80q-33 0-56.5-23.5T120-160v-560q0-33 23.5-56.5T200-800h40v-80h80v80h320v-80h80v80h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Z",
        )
    }

    /** Material Symbol `calendar_month`. */
    val Plan: ImageVector by lazy {
        symbol(
            "Plan",
            "M200-80q-33 0-56.5-23.5T120-160v-560q0-33 23.5-56.5T200-800h40v-80h80v80h320v-80h80v80h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Zm0-480h560v-80H200v80Zm0 0v-80 80Zm280 240q-17 0-28.5-11.5T440-440q0-17 11.5-28.5T480-480q17 0 28.5 11.5T520-440q0 17-11.5 28.5T480-400Zm-160 0q-17 0-28.5-11.5T280-440q0-17 11.5-28.5T320-480q17 0 28.5 11.5T360-440q0 17-11.5 28.5T320-400Zm320 0q-17 0-28.5-11.5T600-440q0-17 11.5-28.5T640-480q17 0 28.5 11.5T680-440q0 17-11.5 28.5T640-400ZM480-240q-17 0-28.5-11.5T440-280q0-17 11.5-28.5T480-320q17 0 28.5 11.5T520-280q0 17-11.5 28.5T480-240Zm-160 0q-17 0-28.5-11.5T280-280q0-17 11.5-28.5T320-320q17 0 28.5 11.5T360-280q0 17-11.5 28.5T320-240Zm320 0q-17 0-28.5-11.5T600-280q0-17 11.5-28.5T640-320q17 0 28.5 11.5T680-280q0 17-11.5 28.5T640-240Z",
        )
    }

    /** Material Symbol `calendar_month (filled)`. */
    val PlanFilled: ImageVector by lazy {
        symbol(
            "PlanFilled",
            "M480-400q-17 0-28.5-11.5T440-440q0-17 11.5-28.5T480-480q17 0 28.5 11.5T520-440q0 17-11.5 28.5T480-400Zm-160 0q-17 0-28.5-11.5T280-440q0-17 11.5-28.5T320-480q17 0 28.5 11.5T360-440q0 17-11.5 28.5T320-400Zm320 0q-17 0-28.5-11.5T600-440q0-17 11.5-28.5T640-480q17 0 28.5 11.5T680-440q0 17-11.5 28.5T640-400ZM480-240q-17 0-28.5-11.5T440-280q0-17 11.5-28.5T480-320q17 0 28.5 11.5T520-280q0 17-11.5 28.5T480-240Zm-160 0q-17 0-28.5-11.5T280-280q0-17 11.5-28.5T320-320q17 0 28.5 11.5T360-280q0 17-11.5 28.5T320-240Zm320 0q-17 0-28.5-11.5T600-280q0-17 11.5-28.5T640-320q17 0 28.5 11.5T680-280q0 17-11.5 28.5T640-240ZM200-80q-33 0-56.5-23.5T120-160v-560q0-33 23.5-56.5T200-800h40v-80h80v80h320v-80h80v80h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Z",
        )
    }

    /** Material Symbol `shopping_cart`. */
    val Shopping: ImageVector by lazy {
        symbol(
            "Shopping",
            "M280-80q-33 0-56.5-23.5T200-160q0-33 23.5-56.5T280-240q33 0 56.5 23.5T360-160q0 33-23.5 56.5T280-80Zm400 0q-33 0-56.5-23.5T600-160q0-33 23.5-56.5T680-240q33 0 56.5 23.5T760-160q0 33-23.5 56.5T680-80ZM246-720l96 200h280l110-200H246Zm-38-80h590q23 0 35 20.5t1 41.5L692-482q-11 20-29.5 31T622-440H324l-44 80h480v80H280q-45 0-68-39.5t-2-78.5l54-98-144-304H40v-80h130l38 80Zm134 280h280-280Z",
        )
    }

    /** Material Symbol `shopping_cart (filled)`. */
    val ShoppingFilled: ImageVector by lazy {
        symbol(
            "ShoppingFilled",
            "M280-80q-33 0-56.5-23.5T200-160q0-33 23.5-56.5T280-240q33 0 56.5 23.5T360-160q0 33-23.5 56.5T280-80Zm400 0q-33 0-56.5-23.5T600-160q0-33 23.5-56.5T680-240q33 0 56.5 23.5T760-160q0 33-23.5 56.5T680-80ZM208-800h590q23 0 35 20.5t1 41.5L692-482q-11 20-29.5 31T622-440H324l-44 80h480v80H280q-45 0-68-39.5t-2-78.5l54-98-144-304H40v-80h130l38 80Z",
        )
    }

    /** Material Symbol `menu_book`. */
    val Recipes: ImageVector by lazy {
        symbol(
            "Recipes",
            "M560-564v-68q33-14 67.5-21t72.5-7q26 0 51 4t49 10v64q-24-9-48.5-13.5T700-600q-38 0-73 9.5T560-564Zm0 220v-68q33-14 67.5-21t72.5-7q26 0 51 4t49 10v64q-24-9-48.5-13.5T700-380q-38 0-73 9t-67 27Zm0-110v-68q33-14 67.5-21t72.5-7q26 0 51 4t49 10v64q-24-9-48.5-13.5T700-490q-38 0-73 9.5T560-454ZM260-320q47 0 91.5 10.5T440-278v-394q-41-24-87-36t-93-12q-36 0-71.5 7T120-692v396q35-12 69.5-18t70.5-6Zm260 42q44-21 88.5-31.5T700-320q36 0 70.5 6t69.5 18v-396q-33-14-68.5-21t-71.5-7q-47 0-93 12t-87 36v394Zm-40 118q-48-38-104-59t-116-21q-42 0-82.5 11T100-198q-21 11-40.5-1T40-234v-482q0-11 5.5-21T62-752q46-24 96-36t102-12q58 0 113.5 15T480-740q51-30 106.5-45T700-800q52 0 102 12t96 36q11 5 16.5 15t5.5 21v482q0 23-19.5 35t-40.5 1q-37-20-77.5-31T700-240q-60 0-116 21t-104 59ZM280-494Z",
            autoMirror = true,
        )
    }

    /** Material Symbol `menu_book (filled)`. */
    val RecipesFilled: ImageVector by lazy {
        symbol(
            "RecipesFilled",
            "M560-564v-68q33-14 67.5-21t72.5-7q26 0 51 4t49 10v64q-24-9-48.5-13.5T700-600q-38 0-73 9.5T560-564Zm0 220v-68q33-14 67.5-21t72.5-7q26 0 51 4t49 10v64q-24-9-48.5-13.5T700-380q-38 0-73 9t-67 27Zm0-110v-68q33-14 67.5-21t72.5-7q26 0 51 4t49 10v64q-24-9-48.5-13.5T700-490q-38 0-73 9.5T560-454Zm-40 176q44-21 88.5-31.5T700-320q36 0 70.5 6t69.5 18v-396q-33-14-68.5-21t-71.5-7q-47 0-93 12t-87 36v394Zm-40 118q-48-38-104-59t-116-21q-42 0-82.5 11T100-198q-21 11-40.5-1T40-234v-482q0-11 5.5-21T62-752q47-23 96.5-35.5T260-800q58 0 113.5 15T480-740q51-30 106.5-45T700-800q52 0 101.5 12.5T898-752q11 5 16.5 15t5.5 21v482q0 23-19.5 35t-40.5 1q-37-20-77.5-31T700-240q-60 0-116 21t-104 59Z",
            autoMirror = true,
        )
    }

    /** Material Symbol `leaderboard`. */
    val Progress: ImageVector by lazy {
        symbol(
            "Progress",
            "M160-200h160v-320H160v320Zm240 0h160v-560H400v560Zm240 0h160v-240H640v240ZM80-120v-480h240v-240h320v320h240v400H80Z",
        )
    }

    /** Material Symbol `leaderboard (filled)`. */
    val ProgressFilled: ImageVector by lazy {
        symbol(
            "ProgressFilled",
            "M80-120v-480h220v480H80Zm290 0v-720h220v720H370Zm290 0v-400h220v400H660Z",
        )
    }

    /** Material Symbol `lock`. */
    val Lock: ImageVector by lazy {
        symbol(
            "Lock",
            "M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80ZM240-160v-400 400Z",
        )
    }

    /** Material Symbol `lock_open`. */
    val LockOpen: ImageVector by lazy {
        symbol(
            "LockOpen",
            "M240-640h360v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85h-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640Zm0 480h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM240-160v-400 400Z",
        )
    }

    /** Material Symbol `swap_horiz`. */
    val Swap: ImageVector by lazy {
        symbol(
            "Swap",
            "M280-160 80-360l200-200 56 57-103 103h287v80H233l103 103-56 57Zm400-240-56-57 103-103H440v-80h287L624-743l56-57 200 200-200 200Z",
        )
    }

    /** Material Symbol `star`. */
    val Star: ImageVector by lazy {
        symbol(
            "Star",
            "m354-287 126-76 126 77-33-144 111-96-146-13-58-136-58 135-146 13 111 97-33 143ZM233-120l65-281L80-590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Zm247-350Z",
        )
    }

    /** Material Symbol `star (filled)`. */
    val StarFilled: ImageVector by lazy {
        symbol(
            "StarFilled",
            "m233-120 65-281L80-590l288-25 112-265 112 265 288 25-218 189 65 281-247-149-247 149Z",
        )
    }

    /** Material Symbol `warning`. */
    val Warning: ImageVector by lazy {
        symbol(
            "Warning",
            "m40-120 440-760 440 760H40Zm138-80h604L480-720 178-200Zm302-40q17 0 28.5-11.5T520-280q0-17-11.5-28.5T480-320q-17 0-28.5 11.5T440-280q0 17 11.5 28.5T480-240Zm-40-120h80v-200h-80v200Zm40-100Z",
        )
    }

    /** Material Symbol `error`. */
    val Error: ImageVector by lazy {
        symbol(
            "Error",
            "M480-280q17 0 28.5-11.5T520-320q0-17-11.5-28.5T480-360q-17 0-28.5 11.5T440-320q0 17 11.5 28.5T480-280Zm-40-160h80v-240h-80v240Zm40 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z",
        )
    }

    /** Material Symbol `nutrition`. */
    val SectionFruitVeg: ImageVector by lazy {
        symbol(
            "SectionFruitVeg",
            "M480-120q-117 0-198.5-81.5T200-400q0-94 55.5-168.5T401-669q-20-5-39-14.5T328-708q-33-33-42.5-78.5T281-879q47-5 92.5 4.5T452-832q23 23 33.5 52t13.5 61q13-31 31.5-58.5T572-828q11-11 28-11t28 11q11 11 11 28t-11 28q-22 22-39 48.5T564-667q88 28 142 101.5T760-400q0 117-81.5 198.5T480-120Zm0-80q83 0 141.5-58.5T680-400q0-83-58.5-141.5T480-600q-83 0-141.5 58.5T280-400q0 83 58.5 141.5T480-200Zm0-200Z",
        )
    }

    /** Material Symbol `bakery_dining`. */
    val SectionBread: ImageVector by lazy {
        symbol(
            "SectionBread",
            "M804-282q17 9 30-4t4-30l-58-108-42 108 66 34Zm-200-38h48l96-238q3-8-1.5-13.5T736-580l-80-32q-9-3-17.5 2T628-596l-24 276Zm-296 0h48l-24-276q-2-11-10.5-15t-17.5-1l-80 32q-8 3-11.5 8.5T212-558l96 238Zm-152 38 66-34-42-108-58 108q-9 17 4 30t30 4Zm280-38h88l30-338q2-9-4.5-15.5T534-680H426q-8 0-14.5 6.5T406-658l30 338ZM138-200q-42 0-70-31.5T40-306q0-12 3.5-23.5T52-352l88-168q-14-40 1-79t53-55l80-32q14-5 28-7t28 1q14-29 39-48.5t57-19.5h108q32 0 57 19.5t39 48.5q14-2 28-.5t28 6.5l80 32q40 16 56 55t-2 77l88 168q6 11 9 23t3 25q0 45-30.5 75.5T814-200q-11 0-22-2.5t-22-7.5l-62-30H250l-56 30q-13 7-27.5 8.5T138-200Zm342-280Z",
        )
    }

    /** Material Symbol `lunch_dining`. */
    val SectionDeli: ImageVector by lazy {
        symbol(
            "SectionDeli",
            "M160-120q-33 0-56.5-23.5T80-200v-120h800v120q0 33-23.5 56.5T800-120H160Zm0-120v40h640v-40H160Zm320-180q-36 0-57 20t-77 20q-56 0-76-20t-56-20q-36 0-57 20t-77 20v-80q36 0 57-20t77-20q56 0 76 20t56 20q36 0 57-20t77-20q56 0 77 20t57 20q36 0 56-20t76-20q56 0 79 20t55 20v80q-56 0-75-20t-55-20q-36 0-58 20t-78 20q-56 0-77-20t-57-20ZM80-560v-40q0-115 108.5-177.5T480-840q183 0 291.5 62.5T880-600v40H80Zm400-200q-124 0-207.5 31T166-640h628q-23-58-106.5-89T480-760Zm0 520Zm0-400Z",
        )
    }

    /** Material Symbol `oil_barrel`. */
    val SectionSauces: ImageVector by lazy {
        symbol(
            "SectionSauces",
            "M160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200h40v-240h-40q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h40v-240h-40q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840h640q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-760h-40v240h40q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440h-40v240h40q17 0 28.5 11.5T840-160q0 17-11.5 28.5T800-120H160Zm120-80h400v-240q-17 0-28.5-11.5T640-480q0-17 11.5-28.5T680-520v-240H280v240q17 0 28.5 11.5T320-480q0 17-11.5 28.5T280-440v240Zm200-120q50 0 85-34.5t35-83.5q0-39-22.5-67T480-620q-75 86-97.5 114.5T360-438q0 49 35 83.5t85 34.5ZM280-200v-560 560Z",
        )
    }

    /** Material Symbol `kebab_dining`. */
    val SectionMeat: ImageVector by lazy {
        symbol(
            "SectionMeat",
            "M250-40v-160h-30q-42 0-71-29t-29-71q0-42 29-71t71-29h30v-40H120v-200h130v-40h-30q-42 0-71-29t-29-71q0-42 29-71t71-29h30v-40h60v40h30q42 0 71 29t29 71q0 42-29 71t-71 29h-30v40h130v200H310v40h30q42 0 71 29t29 71q0 42-29 71t-71 29h-30v160h-60Zm400 0v-160h-30q-42 0-71-29t-29-71q0-42 29-71t71-29h30v-40H520v-200h130v-40h-30q-42 0-71-29t-29-71q0-42 29-71t71-29h30v-40h60v40h30q42 0 71 29t29 71q0 42-29 71t-71 29h-30v40h130v200H710v40h30q42 0 71 29t29 71q0 42-29 71t-71 29h-30v160h-60ZM220-760h120q8 0 14-6t6-14q0-8-6-14t-14-6H220q-8 0-14 6t-6 14q0 8 6 14t14 6Zm400 0h120q8 0 14-6t6-14q0-8-6-14t-14-6H620q-8 0-14 6t-6 14q0 8 6 14t14 6ZM200-520h160v-40H200v40Zm400 0h160v-40H600v40ZM220-280h120q8 0 14-6t6-14q0-8-6-14t-14-6H220q-8 0-14 6t-6 14q0 8 6 14t14 6Zm400 0h120q8 0 14-6t6-14q0-8-6-14t-14-6H620q-8 0-14 6t-6 14q0 8 6 14t14 6ZM200-760v-40 40Zm400 0v-40 40ZM200-520v-40 40Zm400 0v-40 40ZM200-280v-40 40Zm400 0v-40 40Z",
        )
    }

    /** Material Symbol `set_meal`. */
    val SectionFish: ImageVector by lazy {
        symbol(
            "SectionFish",
            "M120-360q-33 0-56.5-23.5T40-440v-360q0-33 23.5-56.5T120-880h720q33 0 56.5 23.5T920-800v360q0 33-23.5 56.5T840-360H120Zm0-440v360h720v-360H120Zm3 580-3-60 719-38 3 60-719 38Zm-3 99v-60h720v60H120Zm290-379q74 0 142.5-26T672-606q6 42 44 64t84 22v-200q-46 0-84 22.5T672-632q-53-52-120.5-80T410-740q-79 0-152 27.5T140-620q45 65 118 92.5T410-500ZM120-800v360-360Z",
        )
    }

    /** Material Symbol `eco`. */
    val SectionVegetarian: ImageVector by lazy {
        symbol(
            "SectionVegetarian",
            "M216-176q-45-45-70.5-104T120-402q0-63 24-124.5T222-642q35-35 86.5-60t122-39.5Q501-756 591.5-759t202.5 7q8 106 5 195t-16.5 160.5q-13.5 71.5-38 125T684-182q-53 53-112.5 77.5T450-80q-65 0-127-25.5T216-176Zm112-16q29 17 59.5 24.5T450-160q46 0 91-18.5t86-59.5q18-18 36.5-50.5t32-85Q709-426 716-500.5t2-177.5q-49-2-110.5-1.5T485-670q-61 9-116 29t-90 55q-45 45-62 89t-17 85q0 59 22.5 103.5T262-246q42-80 111-153.5T534-520q-72 63-125.5 142.5T328-192Zm0 0Zm0 0Z",
        )
    }

    /** Material Symbol `egg_alt`. */
    val SectionDairy: ImageVector by lazy {
        symbol(
            "SectionDairy",
            "M640-80q-67 0-101.5-22.5T480-150q-19-20-36.5-35T399-200q-45 0-100-15.5t-103.5-51Q147-302 114-359T80-499q-2-167 82.5-274T399-880q71 0 120 20.5t84.5 51.5q35.5 31 60 68.5T710-667q12 20 24 36.5t26 30.5q60 60 90 105t30 136q0 120-74.5 199.5T640-80Zm0-80q57 0 108.5-56.5T800-359q0-66-19.5-97T704-544q-21-20-37.5-44.5T633-639q-41-65-87-113t-147-48q-129 0-185 92.5T160-500q1 67 29 110t66.5 67.5Q294-298 334-289t65 9q51 0 82 24.5t51 45.5q22 23 42.5 36.5T640-160ZM480-340q58 0 99-41t41-99q0-58-41-99t-99-41q-58 0-99 41t-41 99q0 58 41 99t99 41Zm-1-140Z",
        )
    }

    /** Material Symbol `takeout_dining`. */
    val SectionReadyMeals: ImageVector by lazy {
        symbol(
            "SectionReadyMeals",
            "M312-240h338l19-280H292l20 280Zm-26-360h389l3-50-112-110H394L282-650l4 50Zm-76 68L80-662l56-56 64 64-2-24 162-162h240l162 162-2 24 64-64 56 56-130 130H210Zm28 372-28-372h540l-28 372H238Zm242-440Zm1 80Z",
        )
    }

    /** Material Symbol `rice_bowl`. */
    val SectionWorld: ImageVector by lazy {
        symbol(
            "SectionWorld",
            "M320-80v-70q-105-42-172.5-130T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 112-67.5 200T640-150v70H320Zm80-400h160v-310q-20-5-40-7.5t-40-2.5q-20 0-40 2.5t-40 7.5v310Zm-240 0h160v-277q-75 43-117.5 117T160-480Zm480 0h160q0-86-42.5-160T640-757v277ZM400-160h160v-44q72-29 133-76.5T784-400H176q30 72 91 119.5T400-204v44Zm0 0h160-160Z",
        )
    }

    /** Material Symbol `inventory_2`. */
    val SectionTins: ImageVector by lazy {
        symbol(
            "SectionTins",
            "M200-80q-33 0-56.5-23.5T120-160v-451q-18-11-29-28.5T80-680v-120q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v120q0 23-11 40.5T840-611v451q0 33-23.5 56.5T760-80H200Zm0-520v440h560v-440H200Zm-40-80h640v-120H160v120Zm200 280h240v-80H360v80Zm120 20Z",
        )
    }

    /** Material Symbol `coffee`. */
    val SectionBreakfast: ImageVector by lazy {
        symbol(
            "SectionBreakfast",
            "M440-240q-117 0-198.5-81.5T160-520v-240q0-33 23.5-56.5T240-840h500q58 0 99 41t41 99q0 58-41 99t-99 41h-20v40q0 117-81.5 198.5T440-240ZM240-640h400v-120H240v120Zm200 320q83 0 141.5-58.5T640-520v-40H240v40q0 83 58.5 141.5T440-320Zm280-320h20q25 0 42.5-17.5T800-700q0-25-17.5-42.5T740-760h-20v120ZM160-120v-80h640v80H160Zm280-440Z",
        )
    }

    /** Material Symbol `cake`. */
    val SectionBaking: ImageVector by lazy {
        symbol(
            "SectionBaking",
            "M160-80q-17 0-28.5-11.5T120-120v-200q0-33 23.5-56.5T200-400v-160q0-33 23.5-56.5T280-640h160v-58q-18-12-29-29t-11-41q0-15 6-29.5t18-26.5l56-56 56 56q12 12 18 26.5t6 29.5q0 24-11 41t-29 29v58h160q33 0 56.5 23.5T760-560v160q33 0 56.5 23.5T840-320v200q0 17-11.5 28.5T800-80H160Zm120-320h400v-160H280v160Zm-80 240h560v-160H200v160Zm80-240h400-400Zm-80 240h560-560Zm560-240H200h560Z",
        )
    }

    /** Material Symbol `cookie`. */
    val SectionSnacks: ImageVector by lazy {
        symbol(
            "SectionSnacks",
            "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-75 29-147t81-128.5q52-56.5 125-91T475-881q21 0 43 2t45 7q-9 45 6 85t45 66.5q30 26.5 71.5 36.5t85.5-5q-26 59 7.5 113t99.5 56q1 11 1.5 20.5t.5 20.5q0 82-31.5 154.5t-85.5 127q-54 54.5-127 86T480-80Zm-60-480q25 0 42.5-17.5T480-620q0-25-17.5-42.5T420-680q-25 0-42.5 17.5T360-620q0 25 17.5 42.5T420-560Zm-80 200q25 0 42.5-17.5T400-420q0-25-17.5-42.5T340-480q-25 0-42.5 17.5T280-420q0 25 17.5 42.5T340-360Zm260 40q17 0 28.5-11.5T640-360q0-17-11.5-28.5T600-400q-17 0-28.5 11.5T560-360q0 17 11.5 28.5T600-320ZM480-160q122 0 216.5-84T800-458q-50-22-78.5-60T683-603q-77-11-132-66t-68-132q-80-2-140.5 29t-101 79.5Q201-644 180.5-587T160-480q0 133 93.5 226.5T480-160Zm0-324Z",
        )
    }

    /** Material Symbol `local_drink`. */
    val SectionDrinks: ImageVector by lazy {
        symbol(
            "SectionDrinks",
            "M279-80q-31 0-53.5-20.5T200-151l-80-729h720l-80 729q-3 30-25.5 50.5T681-80H279Zm-43-480 44 400h400l44-400H236Zm-10-80h508l16-160H210l16 160Zm254 360q-14 0-24-10t-10-24q0-15 8.5-34.5T480-393q17 25 25.5 44.5T514-314q0 14-10 24t-24 10Zm0 80q48 0 81-33t33-81q0-41-26.5-89T480-520q-61 69-87.5 117T366-314q0 48 33 81t81 33Zm-244 40h488-488Z",
        )
    }

    /** Material Symbol `ac_unit`. */
    val SectionFrozen: ImageVector by lazy {
        symbol(
            "SectionFrozen",
            "M440-80v-166L310-118l-56-56 186-186v-80h-80L174-254l-56-56 128-130H80v-80h166L118-650l56-56 186 186h80v-80L254-786l56-56 130 128v-166h80v166l130-128 56 56-186 186v80h80l186-186 56 56-128 130h166v80H714l128 130-56 56-186-186h-80v80l186 186-56 56-130-128v166h-80Z",
        )
    }

    /** Material Symbol `cleaning_services`. */
    val SectionNonFood: ImageVector by lazy {
        symbol(
            "SectionNonFood",
            "M120-40v-280q0-83 58.5-141.5T320-520h40v-320q0-33 23.5-56.5T440-920h80q33 0 56.5 23.5T600-840v320h40q83 0 141.5 58.5T840-320v280H120Zm80-80h80v-120q0-17 11.5-28.5T320-280q17 0 28.5 11.5T360-240v120h80v-120q0-17 11.5-28.5T480-280q17 0 28.5 11.5T520-240v120h80v-120q0-17 11.5-28.5T640-280q17 0 28.5 11.5T680-240v120h80v-200q0-50-35-85t-85-35H320q-50 0-85 35t-35 85v200Zm320-400v-320h-80v320h80Zm0 0h-80 80Z",
        )
    }

    /** Material Symbol `category`. */
    val SectionOther: ImageVector by lazy {
        symbol(
            "SectionOther",
            "m260-520 220-360 220 360H260ZM700-80q-75 0-127.5-52.5T520-260q0-75 52.5-127.5T700-440q75 0 127.5 52.5T880-260q0 75-52.5 127.5T700-80Zm-580-20v-320h320v320H120Zm580-60q42 0 71-29t29-71q0-42-29-71t-71-29q-42 0-71 29t-29 71q0 42 29 71t71 29Zm-500-20h160v-160H200v160Zm202-420h156l-78-126-78 126Zm78 0ZM360-340Zm340 80Z",
        )
    }

    /** Every icon, for checking that all of them can be built. */
    internal val all: List<ImageVector> by lazy {
        listOf(
            Back, ChevronLeft, ChevronRight, More, Close, Add, Sync, Refresh, Edit, Person, Settings,
            Generate, Link, Offer, Pantry, Price,
            Today, TodayFilled, Plan, PlanFilled, Shopping, ShoppingFilled, Recipes, RecipesFilled,
            Progress, ProgressFilled, Lock, LockOpen, Swap, Star, StarFilled, Warning, Error,
            SectionFruitVeg, SectionBread, SectionDeli, SectionSauces, SectionMeat, SectionFish,
            SectionVegetarian, SectionDairy, SectionReadyMeals, SectionWorld, SectionTins,
            SectionBreakfast, SectionBaking, SectionSnacks, SectionDrinks, SectionFrozen,
            SectionNonFood, SectionOther,
        )
    }
}

/**
 * The icon of the shop section called [title] (a shopping item's `category`), "Other" for a
 * title this version does not know. The API carries the title, not the section's key, so the
 * lookup is by title; an exhaustive `when` makes a new [ShopSection] fail to compile until it has an icon.
 */
fun sectionIcon(title: String): ImageVector =
    when (ShopSection.entries.firstOrNull { it.title == title } ?: ShopSection.OTHER) {
        ShopSection.FRUIT_VEG -> AppIcons.SectionFruitVeg
        ShopSection.BREAD -> AppIcons.SectionBread
        ShopSection.DELI -> AppIcons.SectionDeli
        ShopSection.SAUCES -> AppIcons.SectionSauces
        ShopSection.MEAT -> AppIcons.SectionMeat
        ShopSection.FISH -> AppIcons.SectionFish
        ShopSection.VEGETARIAN -> AppIcons.SectionVegetarian
        ShopSection.DAIRY -> AppIcons.SectionDairy
        ShopSection.READY_MEALS -> AppIcons.SectionReadyMeals
        ShopSection.WORLD -> AppIcons.SectionWorld
        ShopSection.TINS -> AppIcons.SectionTins
        ShopSection.BREAKFAST -> AppIcons.SectionBreakfast
        ShopSection.BAKING -> AppIcons.SectionBaking
        ShopSection.SNACKS -> AppIcons.SectionSnacks
        ShopSection.DRINKS -> AppIcons.SectionDrinks
        ShopSection.FROZEN -> AppIcons.SectionFrozen
        ShopSection.NON_FOOD -> AppIcons.SectionNonFood
        ShopSection.OTHER -> AppIcons.SectionOther
    }

/**
 * Builds a 24 dp icon from the path of a Material Symbol's SVG. Those are drawn in a square of
 * [viewport] units whose top is at y = -[viewport] (`viewBox="0 -960 960 960"`), hence the shift
 * down; the few older ones drawn in a plain 24 × 24 square pass `viewport = 24f` and are not shifted.
 */
private fun symbol(name: String, path: String, viewport: Float = 960f, autoMirror: Boolean = false): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
        autoMirror = autoMirror,
    )
        .addGroup(translationY = if (viewport == 24f) 0f else viewport)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
