# Sunmi V2 Photo & Video Printer

Prints arbitrary photos, and videos frame-by-frame, on a Sunmi V2's built-in
thermal printer.

## Sunmi printing API — what I found

Sunmi's printer is accessed through their **official inner-printer SDK**
(`com.sunmi.peripheral.printer`), not a generic Android print API. Three ways
exist to reach it; this app uses the recommended first one.

| Method | When to use |
|---|---|
| **Print service library** (`com.sunmi:printerlibrary`) | Standard approach for native Android apps. What this project uses. |
| **Virtual Bluetooth / raw ESC-POS** | For apps that already speak ESC/POS to other thermal printers and want a drop-in. |
| **JS bridge** | Web/H5 apps running inside Sunmi's browser shell. |

Core classes (all in `com.sunmi.peripheral.printer`):
- `InnerPrinterManager` — singleton that binds/unbinds to the Sunmi print
  service (`InnerPrinterManager.getInstance().bindService(context, callback)`).
- `InnerPrinterCallback` — reports `onConnected(SunmiPrinterService)` /
  `onDisconnected()` once the bind completes (binding is async).
- `SunmiPrinterService` — the actual interface with all print methods
  (`printText`, `printBitmap`, `lineWrap`, `printQRCode`, raw ESC/POS via
  `sendRAWData`, cash-drawer control, etc.). This is the same surface as the
  underlying AIDL `IWoyouService` interface.
- `InnerResultCallback` — async result callback for each print call
  (`onRunResult(Boolean)`, `onRaiseException`, `onReturnString`,
  `onPrintResult`).

The relevant call for images is:

```java
sunmiPrinterService.printBitmap(bitmap, InnerResultCallback);
```

which the service internally thresholds/scales to the print head width
(~384px on 58mm paper, ~576px on 80mm paper). Because prints are physically
sequential, this app waits for each `InnerResultCallback` before sending the
next bitmap.

Sources consulted:
- Sunmi Developer Docs, V2 device page → "Printing Service"
  https://docs.sunmi.com/en/documentation/mobile-products/v2/
- "SUNMI Printing SDK Overview" (Integration Guide)
  https://developer.sunmi.com/docs/en-US/cdixeghjk491/xdzceghjk502
- "SUNMI Inbuilt Printer Developer Documentation" (PDF — full class/method
  reference, binding lifecycle, AIDL details)
  https://cdn.sunmi.com/public/generalfile/mgt-document/841c6680d673447ba9c5d9b1e1131d01.pdf
- Community reference for method signatures incl. `printBitmapCustom`
  https://github.com/FelOrtiz/SunmiV2-Android-Library

## What this app does

- **Print Photo**: pick any image from the gallery → an adjustment dialog with a
  **live print preview** lets you set **scale** (10–100% of the printer's dot
  width), **brightness** (-100..+100, shifted before dithering), and
  **alignment** (left/center/right, via `setAlignment()`). The preview shows
  the *actual* scaled + brightness-adjusted + Floyd–Steinberg-dithered
  1-bit image composed onto a paper-width canvas at the chosen alignment -
  i.e. exactly what will come out of the printer, not just the original
  photo - updated live (debounced) as you move the sliders. Once confirmed,
  the same pipeline runs at full resolution and is sent to `printBitmap()`.
- **Print Video (frame by frame)**: pick any video → choose a sampling
  interval (ms) → the same scale/brightness/alignment dialog (with live
  preview) appears once, using the first extracted frame, and is applied to
  every frame → `MediaMetadataRetriever.getFrameAtTime()` walks the timeline
  at that interval → each frame is processed the same way as a photo and
  printed in order, one after another, as a paper "flipbook."
  (A real printer physically cannot keep up with 24–60fps video, so true
  per-codec-frame printing isn't practical — this samples the timeline
  instead, and you control the density.)
- **Print Custom Text**: a rich-text composer dialog with a multi-line input,
  **bold** toggle, **font size** (Small/Medium/Large/X-Large), and
  **alignment** (left/center/right). Bold is sent as a raw ESC/POS command
  (`ESC E n`) since the SDK's `printText`/`printTextWithFont` calls don't
  expose bold directly; font size and alignment use the SDK's own
  `setFontSize()` / `setAlignment()` calls, which persist until changed.

## App icon

A generated launcher icon (printer + photo printout motif, teal background)
is included at all standard densities (`mipmap-mdpi` through `-xxxhdpi`),
both square and round variants, wired up via `android:icon` /
`android:roundIcon` in the manifest. Regenerate it any time with
`python3 generate_icons.py` if you want a different look — the script draws
everything procedurally with Pillow, no external image assets needed.

#### Media Processing
- Package Name: com.cadrega.posprinter
- Architecture: Kotlin Coroutines with ViewBinding and Material 3 components.
- Image Processing: Floyd-Steinberg dithering is implemented in PrintImageUtils.kt to convert grayscale images into high-quality 1-bit dot patterns suitable for thermal heads.
- Video Extraction: Handled via MediaMetadataRetriever in VideoFrameExtractor.kt, allowing for precise timestamp-based frame reacquisition.
- History Management: PrintHistoryManager.kt persists metadata and original high-quality media in the app's internal storage, allowing for re-adjustment without quality loss.

### Customization

To adjust for different paper widths, modify the printerWidthPx constant in MainActivity.kt:
- 58mm Paper: 384px (Default)
- 80mm Paper: 576px (PRINTER_WIDTH_80MM)

### Launcher Icons

The application includes adaptive icons for modern Android versions and legacy PNGs for older devices. These can be regenerated using the generate_icons.py script if the branding needs to be updated.

### References and Resources
- Official Developer Documentation: https://docs.sunmi.com/en/documentation/mobile-products/v2/
- Printing SDK Overview: https://developer.sunmi.com/docs/en-US/cdixeghjk491/xdzceghjk502
- Inbuilt Printer PDF Guide: https://cdn.sunmi.com/public/generalfile/mgt-document/841c6680d673447ba9c5d9b1e1131d01.pdf
