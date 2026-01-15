package com.example.meetingbingo.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Captures screenshots using MediaProjection API.
 * Adapted from hexapa's ScreenCapture implementation.
 *
 * This provides an alternative to AccessibilityService.takeScreenshot() when
 * accessibility service is not available or desired.
 */
class MediaProjectionScreenCapture(
    private val context: Context,
    private val minSpacingMs: Int = 0
) : ImageReader.OnImageAvailableListener {

    companion object {
        private const val TAG = "MediaProjectionCapture"
        private const val PIXEL_FORMAT = PixelFormat.RGBA_8888
        private val BITMAP_CONFIG = Bitmap.Config.ARGB_8888

        // Maximum resolution for screenshot capture (1920x1088 = ~2.1MP)
        private const val MAX_CAPTURE_PIXELS = 1920 * 1088
    }

    private var width: Int = 0
    private var height: Int = 0
    private var densityDpi: Int = 0

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var onCaptureListener: ((Bitmap) -> Unit)? = null

    private var lastCaptureTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setScreenParameters()
    }

    /**
     * Start continuous screen capture.
     * The onCaptureListener will be called whenever a new frame is available.
     */
    fun startCapture(mediaProjection: MediaProjection, onCapture: (Bitmap) -> Unit) {
        Log.d(TAG, "startCapture")
        this.onCaptureListener = onCapture

        if (virtualDisplay == null) {
            virtualDisplay = createVirtualDisplay(mediaProjection)
        }
    }

    /**
     * Take a single screenshot and return it via callback.
     * Useful for one-off OCR operations.
     */
    fun takeSingleScreenshot(mediaProjection: MediaProjection, onResult: (Bitmap?) -> Unit) {
        Log.d(TAG, "takeSingleScreenshot")

        // Set up a one-shot listener
        var captured = false
        startCapture(mediaProjection) { bitmap ->
            if (!captured) {
                captured = true
                // Stop capture after getting one frame
                mainHandler.post {
                    stop()
                    onResult(bitmap)
                }
            }
        }

        // Timeout fallback - if no frame received within 2 seconds
        mainHandler.postDelayed({
            if (!captured) {
                captured = true
                stop()
                onResult(null)
            }
        }, 2000)
    }

    /**
     * Stop capturing and release resources.
     */
    fun stop() {
        Log.d(TAG, "stop")
        mainHandler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        onCaptureListener = null
    }

    private fun createVirtualDisplay(mediaProjection: MediaProjection): VirtualDisplay {
        val maxImages = 3
        val reader = ImageReader.newInstance(width, height, PIXEL_FORMAT, maxImages)
        this.imageReader = reader
        reader.setOnImageAvailableListener(this, mainHandler)

        Log.d(TAG, "Creating virtual display: ${width}x${height} @ ${densityDpi}dpi")

        return try {
            // Try to create a secure virtual display first
            mediaProjection.createVirtualDisplay(
                "BingoScreenCapture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE,
                reader.surface,
                createCallback(),
                mainHandler
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create secure virtual display, falling back to normal: ${e.message}")
            mediaProjection.createVirtualDisplay(
                "BingoScreenCapture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                createCallback(),
                mainHandler
            )
        }
    }

    private fun createCallback() = object : VirtualDisplay.Callback() {
        override fun onStopped() {
            Log.d(TAG, "VirtualDisplay stopped")
        }

        override fun onResumed() {
            Log.d(TAG, "VirtualDisplay resumed")
        }

        override fun onPaused() {
            Log.d(TAG, "VirtualDisplay paused")
        }
    }

    override fun onImageAvailable(reader: ImageReader) {
        if (onCaptureListener == null || virtualDisplay == null) {
            return
        }

        val now = System.currentTimeMillis()
        mainHandler.removeCallbacksAndMessages(null)

        // Rate limiting: enforce minSpacingMs between captures
        if (now - lastCaptureTime >= minSpacingMs) {
            lastCaptureTime = now
            captureImage(reader)?.let { onCaptureListener?.invoke(it) }
        } else {
            // Delay capture if too soon
            val delay = minSpacingMs + lastCaptureTime - now
            mainHandler.postDelayed({
                lastCaptureTime = System.currentTimeMillis()
                captureImage(reader)?.let { onCaptureListener?.invoke(it) }
            }, delay)
        }
    }

    private fun captureImage(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null

        return try {
            image.planes[0].run {
                // Note: rowStride may be padded to a multiple of 16
                // Create bitmap with the padded width, then crop if needed
                val bitmapWidth = rowStride / pixelStride
                val bitmap = Bitmap.createBitmap(bitmapWidth, height, BITMAP_CONFIG)
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to actual width if there's padding
                if (bitmapWidth > width) {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    cropped
                } else {
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing image: ${e.message}")
            null
        } finally {
            image.close()
        }
    }

    private fun setScreenParameters() {
        val displayMetrics = context.resources.displayMetrics
        var w = displayMetrics.widthPixels
        var h = displayMetrics.heightPixels

        // Scale down if exceeds max pixels while maintaining aspect ratio
        while (w * h > MAX_CAPTURE_PIXELS) {
            w /= 2
            h /= 2
        }

        width = w
        height = h
        densityDpi = displayMetrics.densityDpi

        Log.d(TAG, "Screen parameters: ${width}x${height} @ ${densityDpi}dpi")
    }
}
