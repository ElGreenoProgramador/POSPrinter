# POSPrinter

POSPrinter is a professional Android application designed for high-quality thermal printing on integrated POS hardware. It supports advanced features such as photo printing with Floyd-Steinberg dithering, multi-frame video extraction, batch processing, and Markdown-based text formatting.

## Key Features

- Photo Printing: Import any image and adjust scale, brightness, and alignment with a live dithered preview before printing.
- Video Frame Printing: Select specific frames from any video via a visual grid and print them as a sequential batch.
- Batch Printing: Process multiple photos at once with individual adjustment controls for each image.
- Text and Markdown: Create professionally formatted receipts and documents using basic Markdown syntax (Headers, Lists, Bold).
- External File Support: Import .txt or .md files directly into the text editor.
- Persistent Printing History: View a log of previous print jobs with visual previews and the ability to re-print or re-adjust settings from past actions.
- Hardware Integration: Deep integration with the hardware's inner-printer SDK for reliable, sequential printing.

## User Guide

1. Home Screen: Choose between Photo, Batch, Video, or Text printing features.
2. Adjustments: Every image-based print job opens a dedicated adjustment workspace where you can fine-tune the output.
3. Live Preview: The right-side preview simulates the exact dot-pattern (dithering) that will be printed.
4. History: Tap any item in the history section to see its preview. You can choose to re-print it exactly or adjust its parameters for a new version.

## Developer Documentation

### Project Setup

1. Requirements: Android Studio Hedgehog or newer.
2. Target Hardware: Designed for devices with integrated thermal printers (58mm or 80mm).
3. Dependencies:
   - official printer library: com.sunmi:printerlibrary:1.0.18
   - AndroidX Core, AppCompat, Material 3, and Coroutines.
4. Permissions: The app requires standard media and file importing permissions.

### Technical Implementation

#### Official SDK Integration
The hardware's printer is accessed through the official inner-printer SDK (`com.sunmi.peripheral.printer`).
Core classes:
- `InnerPrinterManager`: Singleton that binds/unbinds to the print service.
- `InnerPrinterCallback`: Reports connection status.
- `SunmiPrinterService`: The actual interface with all print methods (`printText`, `printBitmap`, etc.).
- `InnerResultCallback`: Async result callback for each print call.

Relevant call for images:
```java
sunmiPrinterService.printBitmap(bitmap, InnerResultCallback);
```

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
