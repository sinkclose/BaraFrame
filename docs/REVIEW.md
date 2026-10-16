# BaraFrame 1.3: review of disappearing backgrounds

## Confirmed plugin defects

1. **Rotation invalidated the current image.** Version 1.2 used live display dimensions when reading the cache. Leaving a landscape ClockDesk for portrait Home changed the ratio and could turn a valid cached image into the fallback color. The target is now saved once and remains stable across Home, process recreation and restart. Old caches use their recorded proportions during migration. The user can explicitly capture a new screen size.
2. **Saved file URIs could outlive the corresponding files.** ClockDesk can miss broadcasts while paused; deleting older files could leave its saved URI unreadable. A private read-only provider now resolves both the current endpoint and legacy FileProvider paths to the latest eligible publication. Revision parameters change the cache key on each update without making old URIs depend on old files.
3. **URI permissions end at reboot.** Explicit grants already outlived activity pause, so pause alone did not prove a permission bug. The plugin now restores narrowly scoped read grants and publishes the current image on boot and package replacement. Existing WorkManager tasks are preserved on background requests.

The provider checks the current tag, source, rating and aspect settings every time it opens a file. It never serves an arbitrary requested filesystem path. Tightening the rating cannot expose an older disallowed image through a stale URI. Publication and file opening use the same short lock; decoding and network operations run outside it.

## Crop behavior

The preview and JPEG exporter use the same source rectangle calculation. Dragging changes the crop center; pinch and the slider change zoom from 1x to 4x. The crop never reveals space outside the source. The original JPEG is retained, so subsequent edits do not crop the previously cropped image. A separate local worker renders and atomically publishes the result without requiring a network connection. A crop cannot replace a newer downloaded image. Original and rendered current files are both protected from cleanup.

Draft crop coordinates and frame selection survive activity recreation. Applying a crop is explicit. Source filters and frame proportions are separate controls. Switching to another image starts with a centered crop in the saved frame format.

## ClockDesk review limits

A local ClockDesk checkout was inspected without modification. Its `DynamicBackgroundView.onSurfaceCreated()` reloads the current bitmap and `MainActivity.onResume()` resumes background loading. It does not contain the background plugin broadcast contract used by the installed ClockDesk, so it is insufficient to prove behavior in that release.

If an image still disappears after this update, the next check belongs in the installed ClockDesk version: request the provider again on resume, reopen a valid saved URI, restore the image after OpenGL context recreation, and avoid replacing a plugin background with the default gradient during an unrelated weather refresh. These are investigation points, not confirmed defects in that release.

## Validation

Host JVM tests cover crop boundaries, drag direction, pinch anchoring, limits, 500 image/frame combinations, persisted ratio selection across a landscape-to-portrait transition, old-cache migration, and allowed URI paths. Existing source parser, rating, tag and aspect tests remain part of the check. The APK is compiled and checked with Android lint.

No emulator is used. Native gestures, the exact result in the installed ClockDesk, returning from Home, screen lock/unlock and reboot still need a manual device pass. CI builds the `release` variant using the existing signing certificate for compatibility with previously installed APKs. Signing material is stored in GitHub Actions secrets; this is not a Play Store release signing setup.
