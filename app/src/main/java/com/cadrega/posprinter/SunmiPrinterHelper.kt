package com.cadrega.posprinter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin wrapper around Sunmi's official inner-printer SDK.
 *
 * Reference docs used:
 *  - Sunmi Developer Docs, V2 device page -> "Printing Service"
 *    https://docs.sunmi.com/en/documentation/mobile-products/v2/
 *  - "SUNMI Printing SDK Overview" (Integration Guide)
 *    https://developer.sunmi.com/docs/en-US/cdixeghjk491/xdzceghjk502
 *  - "SUNMI Inbuilt Printer Developer Documentation" (PDF, describes
 *    InnerPrinterManager / SunmiPrinterService / InnerResultCallback)
 *    https://cdn.sunmi.com/public/generalfile/mgt-document/841c6680d673447ba9c5d9b1e1131d01.pdf
 *
 * Key facts encoded here:
 *  - You must bindService() before calling any print method; the connection
 *    is delivered asynchronously via InnerPrinterCallback.onConnected().
 *  - printBitmap(Bitmap, InnerResultCallback) is the standard "print an
 *    arbitrary image" call. The service internally dithers/scales the image
 *    to the printer's paper width.
 *  - Thermal printer heads are physically ~384px wide (58mm paper) or
 *    ~576px wide (80mm paper). Sending anything wider just gets scaled down
 *    by the service, but pre-scaling ourselves gives sharper, faster prints.
 *  - Because printing is a physical, sequential process, each bitmap must be
 *    fully accepted by the service before the next one is sent - otherwise
 *    the internal buffer can drop frames. We enforce this by waiting on the
 *    InnerResultCallback for each print call before returning.
 */
class SunmiPrinterHelper(private val context: Context) {

    private val settingsManager = SettingsManager(context)

    companion object {
        private const val TAG = "SunmiPrinterHelper"
        const val PRINTER_WIDTH_58MM = 384
        const val PRINTER_WIDTH_80MM = 576

        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }

    private var printerService: SunmiPrinterService? = null
    private var connectedCallback: (() -> Unit)? = null
    private var disconnectedCallback: (() -> Unit)? = null

    private val innerPrinterCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService) {
            Log.i(TAG, "Printer service connected")
            printerService = service
            connectedCallback?.invoke()
        }

        override fun onDisconnected() {
            Log.w(TAG, "Printer service disconnected")
            printerService = null
            disconnectedCallback?.invoke()
        }
    }

    val isConnected: Boolean
        get() = printerService != null

    /** Binds to the Sunmi print service. Safe to call multiple times. */
    fun connect(onConnected: () -> Unit, onDisconnected: () -> Unit = {}) {
        connectedCallback = onConnected
        disconnectedCallback = onDisconnected
        try {
            val bound = InnerPrinterManager.getInstance().bindService(context, innerPrinterCallback)
            if (!bound) {
                Log.e(TAG, "bindService returned false - is this a POSPrinter hardware?")
            }
        } catch (e: InnerPrinterException) {
            Log.e(TAG, "bindService failed", e)
        }
    }

    fun disconnect() {
        try {
            InnerPrinterManager.getInstance().unBindService(context, innerPrinterCallback)
        } catch (e: InnerPrinterException) {
            Log.e(TAG, "unBindService failed", e)
        }
        printerService = null
    }

    /** Returns the printer model string from the service. */
    fun getPrinterModel(): String? {
        return try {
            printerService?.getPrinterModal()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Prints a single bitmap and suspends until the service has accepted it
     * (or failed). Wrapping the async InnerResultCallback in a coroutine lets
     * callers print many images/frames back-to-back with a simple for-loop
     * instead of nested callbacks.
     */
    suspend fun printBitmap(bitmap: Bitmap): Result<Unit> = suspendCancellableCoroutine { cont ->
        val service = printerService
        if (service == null) {
            Log.w(TAG, "printBitmap: Printer not connected, skipping (virtual mode)")
            cont.resume(Result.success(Unit))
            return@suspendCancellableCoroutine
        }
        try {
            service.printBitmap(bitmap, object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) {
                    if (cont.isActive) cont.resume(
                        if (isSuccess) Result.success(Unit)
                        else Result.failure(RuntimeException("Printer reported failure"))
                    )
                }

                override fun onReturnString(result: String?) {
                    // Raw status string from the printer firmware; useful for debugging.
                    Log.d(TAG, "printBitmap onReturnString: $result")
                }

                override fun onRaiseException(code: Int, msg: String?) {
                    if (cont.isActive) cont.resume(
                        Result.failure(RuntimeException("Printer exception $code: $msg"))
                    )
                }

                override fun onPrintResult(code: Int, msg: String?) {
                    // Extended result callback on newer SDK versions; not required
                    // for success/fail decisioning here, just logged.
                    Log.d(TAG, "printBitmap onPrintResult: $code $msg")
                }
            })
        } catch (e: InnerPrinterException) {
            cont.resume(Result.failure(e))
        }
    }

    /**
     * Sets alignment (ALIGN_LEFT / ALIGN_CENTER / ALIGN_RIGHT) for the *next*
     * print call. Applies to both text and bitmaps narrower than the paper
     * width - this is how we implement "align image within layout".
     */
    suspend fun setAlignment(alignment: Int): Result<Unit> = suspendCancellableCoroutine { cont ->
        val service = printerService
        if (service == null) {
            Log.w(TAG, "setAlignment: Printer not connected, skipping (virtual mode)")
            cont.resume(Result.success(Unit))
            return@suspendCancellableCoroutine
        }
        try {
            service.setAlignment(alignment, object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                override fun onReturnString(result: String?) {}
                override fun onRaiseException(code: Int, msg: String?) {
                    if (cont.isActive) cont.resume(Result.failure(RuntimeException("$code: $msg")))
                }
                override fun onPrintResult(code: Int, msg: String?) {}
            })
        } catch (e: InnerPrinterException) {
            cont.resume(Result.failure(e))
        }
    }

    /** Sets bold on/off for subsequent text prints via the standard ESC/POS bold command. */
    suspend fun setBold(bold: Boolean): Result<Unit> = suspendCancellableCoroutine { cont ->
        val service = printerService
        if (service == null) {
            Log.w(TAG, "setBold: Printer not connected, skipping (virtual mode)")
            cont.resume(Result.success(Unit))
            return@suspendCancellableCoroutine
        }
        try {
            // ESC E n  ->  0x1B 0x45 (1 = bold on, 0 = bold off)
            val cmd = byteArrayOf(0x1B, 0x45, if (bold) 1 else 0)
            service.sendRAWData(cmd, object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                override fun onReturnString(result: String?) {}
                override fun onRaiseException(code: Int, msg: String?) {
                    if (cont.isActive) cont.resume(Result.failure(RuntimeException("$code: $msg")))
                }
                override fun onPrintResult(code: Int, msg: String?) {}
            })
        } catch (e: InnerPrinterException) {
            cont.resume(Result.failure(e))
        }
    }

    /** Sets the text font size (in the printer's own point scale, typically 24-46). */
    suspend fun setFontSize(size: Float): Result<Unit> = suspendCancellableCoroutine { cont ->
        val service = printerService
        if (service == null) {
            Log.w(TAG, "setFontSize: Printer not connected, skipping (virtual mode)")
            cont.resume(Result.success(Unit))
            return@suspendCancellableCoroutine
        }
        try {
            service.setFontSize(size, object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                override fun onReturnString(result: String?) {}
                override fun onRaiseException(code: Int, msg: String?) {
                    if (cont.isActive) cont.resume(Result.failure(RuntimeException("$code: $msg")))
                }
                override fun onPrintResult(code: Int, msg: String?) {}
            })
        } catch (e: InnerPrinterException) {
            cont.resume(Result.failure(e))
        }
    }

    /** Prints a line of plain text using whatever alignment/bold/font size is currently set. */
    suspend fun printText(text: String): Result<Unit> = suspendCancellableCoroutine { cont ->
        val service = printerService
        if (service == null) {
            Log.w(TAG, "printText: Printer not connected, skipping (virtual mode)")
            cont.resume(Result.success(Unit))
            return@suspendCancellableCoroutine
        }
        try {
            // The hardware requires a trailing newline for the line to actually feed/print.
            val payload = if (text.endsWith("\n")) text else "$text\n"
            service.printText(payload, object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                override fun onReturnString(result: String?) {}
                override fun onRaiseException(code: Int, msg: String?) {
                    if (cont.isActive) cont.resume(Result.failure(RuntimeException("$code: $msg")))
                }
                override fun onPrintResult(code: Int, msg: String?) {}
            })
        } catch (e: InnerPrinterException) {
            cont.resume(Result.failure(e))
        }
    }

    /**
     * Parses a simple markdown string and prints it line-by-line.
     * Supported:
     * # H1 (Large, Bold, Center)
     * ## H2 (Medium-Large, Bold, Center)
     * ### H3 (Medium, Bold)
     * - or * List items
     * **Bold** (applies to whole line)
     */
    suspend fun printMarkdown(markdown: String): Result<Unit> {
        val scale = settingsManager.fontScale
        val lines = markdown.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("# ") -> {
                    printRichText(trimmed.substring(2), ALIGN_CENTER, true, 38f * scale)
                }
                trimmed.startsWith("## ") -> {
                    printRichText(trimmed.substring(3), ALIGN_CENTER, true, 32f * scale)
                }
                trimmed.startsWith("### ") -> {
                    printRichText(trimmed.substring(4), ALIGN_LEFT, true, 28f * scale)
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val content = trimmed.substring(2)
                    printRichText(" • $content", ALIGN_LEFT, false, 24f * scale)
                }
                trimmed.contains("**") -> {
                    // Simple bold for the whole line if it contains **
                    val clean = trimmed.replace("**", "")
                    printRichText(clean, ALIGN_LEFT, true, 24f * scale)
                }
                else -> {
                    printRichText(line, ALIGN_LEFT, false, 24f * scale)
                }
            }
        }
        return Result.success(Unit)
    }

    /**
     * Convenience wrapper: applies alignment, bold, and font size, prints the
     * text, then resets bold to off so it doesn't leak into later prints.
     */
    suspend fun printRichText(
        text: String,
        alignment: Int = ALIGN_LEFT,
        bold: Boolean = false,
        fontSize: Float = 24f
    ): Result<Unit> {
        setAlignment(alignment)
        setFontSize(fontSize)
        setBold(bold)
        val result = printText(text)
        if (bold) setBold(false) // reset so later prints aren't accidentally bold
        return result
    }

    /** Feeds blank paper lines, e.g. to separate prints or leave a tear-off margin. */
    suspend fun feedPaper(lines: Int = 3): Unit = suspendCancellableCoroutine { cont ->
        val service = printerService
        if (service == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        try {
            service.lineWrap(lines, object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onReturnString(result: String?) {}
                override fun onRaiseException(code: Int, msg: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onPrintResult(code: Int, msg: String?) {}
            })
        } catch (e: InnerPrinterException) {
            cont.resume(Unit)
        }
    }
}
