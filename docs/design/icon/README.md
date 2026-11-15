# The app mark

A plate seen from above between a fork and a knife. The plate's rim is seven segments, the days of the week, with one in amber ("today"), and a tick in the well.

There are two forms, because the full one is too busy to read small or in one colour:

| Form | Source | Used for |
|---|---|---|
| Full: seven days, amber, tick, on the app's green | `mark-full.svg` | Launcher icon foreground, splash screen, favicon, the logged-out settings screen |
| Simplified: plate ring between fork and knife, one colour, drawn on a 24 unit grid | `mark-small.svg` | Themed (monochrome) launcher layer, the update notification icon, any 24 dp use |

The files are the source; the shipped copies are written out by hand in each format and must be kept in step when the mark changes:

- `androidApp/src/main/res/drawable/ic_launcher_foreground.xml` (full), `ic_launcher_monochrome.xml` and `ic_stat_update.xml` (simplified)
- `androidApp/src/main/res/values-v31/themes.xml` and `values-night-v31/themes.xml`: the Android 12+ splash screen shows the launcher foreground on the app's green
- `composeApp/src/wasmJsMain/resources/favicon.svg` (full)
- `composeApp/src/commonMain/.../Illustrations.kt`: the full mark as an `ImageVector` and the four spot illustrations of the empty states

The mark stays inside the centre 66 of the adaptive icon's 108 units, the part every launcher mask leaves visible.
