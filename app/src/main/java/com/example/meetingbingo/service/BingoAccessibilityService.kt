package com.example.meetingbingo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accessibility Service for extracting Meeting ID from Zoom and injecting clicks.
 */
class BingoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BingoAccessibility"

        // Singleton instance for communication with other parts of the app
        var instance: BingoAccessibilityService? = null
            private set

        private val _isServiceEnabled = MutableStateFlow(false)
        val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

        private val _extractedMeetingId = MutableStateFlow<String?>(null)
        val extractedMeetingId: StateFlow<String?> = _extractedMeetingId.asStateFlow()

        // Callback for screenshot OCR detection
        var onDetectionStarted: (() -> Unit)? = null
        var onDetectionSuccess: ((String) -> Unit)? = null
        var onDetectionFailed: ((String) -> Unit)? = null

        // Zoom package names
        private val ZOOM_PACKAGES = setOf(
            "us.zoom.videomeetings",
            "us.zoom.zoompresence"  // Neat Zoom app
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var extractionInProgress = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceEnabled.value = true
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onDestroy() {
        instance = null
        _isServiceEnabled.value = false
        Log.d(TAG, "Accessibility Service destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can monitor events here if needed
        // For now, we use on-demand extraction
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    /**
     * Start the meeting ID extraction process.
     * This will:
     * 1. Tap on screen to show Zoom controls
     * 2. Find and tap "More" button
     * 3. Find and tap "Meeting info"
     * 4. Extract the Meeting ID from the dialog
     * 5. Close the dialog
     */
    fun startMeetingIdExtraction() {
        if (extractionInProgress) {
            Log.d(TAG, "Extraction already in progress")
            return
        }

        extractionInProgress = true
        Log.d(TAG, "Starting meeting ID extraction")

        // Step 1: Tap center of screen to show Zoom controls
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f

        // Tap twice quickly to ensure controls stay visible
        performTap(centerX, centerY) {
            handler.postDelayed({
                performTap(centerX, centerY) {
                    // Step 2: Wait for controls to appear, then find "More" button
                    handler.postDelayed({
                        findAndClickMore()
                    }, 300)
                }
            }, 100)
        }
    }

    private fun findAndClickMore() {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(TAG, "No root node available")
            extractionInProgress = false
            return
        }

        // Look for "More" button by text or content description
        val moreNode = findNodeByText(rootNode, "More")
            ?: findNodeByContentDescription(rootNode, "More")
            ?: findNodeByText(rootNode, "more")

        if (moreNode != null) {
            Log.d(TAG, "Found 'More' button")
            clickNode(moreNode) {
                // Step 3: Wait for menu, then find "Meeting info"
                handler.postDelayed({
                    findAndClickMeetingInfo()
                }, 500)
            }
            moreNode.recycle()
        } else {
            Log.e(TAG, "Could not find 'More' button via tree, tapping at known position")
            // On Neat Frame, More button is typically at around x=790, y=1858
            // This is a fallback when the button isn't found in the accessibility tree
            performTap(790f, 1858f) {
                handler.postDelayed({
                    findAndClickMeetingInfo()
                }, 500)
            }
        }
        rootNode.recycle()
    }

    private fun findAndClickMeetingInfo() {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(TAG, "No root node available")
            extractionInProgress = false
            return
        }

        // Look for "Meeting info" or "Meeting Info" button
        val meetingInfoNode = findNodeByText(rootNode, "Meeting info")
            ?: findNodeByText(rootNode, "Meeting Info")
            ?: findNodeByContentDescription(rootNode, "Meeting info")

        if (meetingInfoNode != null) {
            Log.d(TAG, "Found 'Meeting info' button")
            clickNode(meetingInfoNode) {
                // Step 4: Wait for dialog, then extract Meeting ID
                handler.postDelayed({
                    extractMeetingId()
                }, 500)
            }
            meetingInfoNode.recycle()
        } else {
            Log.e(TAG, "Could not find 'Meeting info' button via tree, tapping at known position")
            // On Neat Frame, Meeting info is typically at around x=776, y=1058 in the More menu
            performTap(776f, 1058f) {
                handler.postDelayed({
                    extractMeetingId()
                }, 500)
            }
        }
        rootNode.recycle()
    }

    private fun extractMeetingId() {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(TAG, "No root node available")
            extractionInProgress = false
            return
        }

        tryExtractMeetingIdDirectly(rootNode)
        rootNode.recycle()
    }

    private fun tryExtractMeetingIdDirectly(rootNode: AccessibilityNodeInfo) {
        // Look for Meeting ID pattern in all text nodes
        // Zoom Meeting IDs are typically 9-11 digits, sometimes with spaces
        val meetingIdPattern = Regex("\\d{3}[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}")

        val allText = mutableListOf<String>()
        collectAllText(rootNode, allText)

        Log.d(TAG, "Collected ${allText.size} text nodes")
        // Log each text for debugging
        allText.forEachIndexed { index, text ->
            Log.d(TAG, "Text[$index]: $text")
        }

        for (text in allText) {
            val match = meetingIdPattern.find(text)
            if (match != null) {
                // Clean up the meeting ID (remove spaces and dashes)
                val meetingId = match.value.replace(Regex("[\\s-]"), "")
                Log.d(TAG, "Found Meeting ID: $meetingId")
                _extractedMeetingId.value = meetingId

                // Close the dialog by pressing back or tapping outside
                handler.postDelayed({
                    closeDialog()
                }, 300)
                return
            }
        }

        // Also look for a node labeled "Meeting ID" and get the next sibling's text
        val meetingIdLabel = findNodeByText(rootNode, "Meeting ID")
        if (meetingIdLabel != null) {
            val parent = meetingIdLabel.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i)
                    if (child != null && child.text != null) {
                        val text = child.text.toString()
                        val match = meetingIdPattern.find(text)
                        if (match != null) {
                            val meetingId = match.value.replace(Regex("[\\s-]"), "")
                            Log.d(TAG, "Found Meeting ID from label sibling: $meetingId")
                            _extractedMeetingId.value = meetingId
                            child.recycle()
                            parent.recycle()
                            meetingIdLabel.recycle()

                            handler.postDelayed({
                                closeDialog()
                            }, 300)
                            return
                        }
                    }
                    child?.recycle()
                }
                parent.recycle()
            }
            meetingIdLabel.recycle()
        }

        Log.d(TAG, "Could not find Meeting ID in accessibility tree, trying screenshot OCR")
        tryExtractMeetingIdFromScreenshot()
    }

    private fun tryExtractMeetingIdFromScreenshot() {
        // Take a screenshot using the accessibility service's screenshot capability (API 30+)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                applicationContext.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        Log.d(TAG, "Screenshot taken successfully")
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        if (bitmap != null) {
                            processScreenshotForMeetingId(bitmap)
                            screenshot.hardwareBuffer.close()
                        } else {
                            Log.e(TAG, "Failed to convert screenshot to bitmap")
                            extractionInProgress = false
                            closeDialog()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        extractionInProgress = false
                        closeDialog()
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Screenshot permission denied: ${e.message}")
            extractionInProgress = false
            closeDialog()
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot error: ${e.message}")
            extractionInProgress = false
            closeDialog()
        }
    }

    private fun processScreenshotForMeetingId(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                Log.e(TAG, "OCR text recognized (full): ${visionText.text}")
                Log.e(TAG, "OCR text length: ${visionText.text.length}")

                // Look for Meeting ID pattern in OCR text
                val meetingIdPattern = Regex("\\d{3}[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}")
                val fullText = visionText.text

                // First look for "Meeting ID" label followed by the number
                val meetingIdLabelPattern = Regex("Meeting\\s*ID[:\\s]*([\\d\\s-]+)")
                val labelMatch = meetingIdLabelPattern.find(fullText)
                if (labelMatch != null) {
                    val potentialId = labelMatch.groupValues[1].replace(Regex("[\\s-]"), "")
                    if (potentialId.length in 9..11 && potentialId.all { it.isDigit() }) {
                        Log.d(TAG, "Found Meeting ID from OCR (with label): $potentialId")
                        _extractedMeetingId.value = potentialId
                        handler.postDelayed({ closeDialog() }, 300)
                        return@addOnSuccessListener
                    }
                }

                // Fallback: look for any meeting ID pattern
                val match = meetingIdPattern.find(fullText)
                if (match != null) {
                    val meetingId = match.value.replace(Regex("[\\s-]"), "")
                    Log.d(TAG, "Found Meeting ID from OCR (pattern match): $meetingId")
                    _extractedMeetingId.value = meetingId
                    handler.postDelayed({ closeDialog() }, 300)
                } else {
                    Log.e(TAG, "Could not find Meeting ID in OCR text")
                    extractionInProgress = false
                    closeDialog()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}")
                extractionInProgress = false
                closeDialog()
            }
    }

    private fun closeDialog() {
        // Try pressing back to close the dialog
        performGlobalAction(GLOBAL_ACTION_BACK)

        handler.postDelayed({
            // Press back again to close the More menu if still open
            performGlobalAction(GLOBAL_ACTION_BACK)
            extractionInProgress = false
            Log.d(TAG, "Extraction complete")
        }, 300)
    }

    private fun collectAllText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.let { texts.add(it.toString()) }
        node.contentDescription?.let { texts.add(it.toString()) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, texts)
                child.recycle()
            }
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
            return AccessibilityNodeInfo.obtain(root)
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, description)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo, onComplete: () -> Unit) {
        // Try to click the node directly
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            onComplete()
            return
        }

        // If not clickable, try to find a clickable parent
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                onComplete()
                return
            }
            val parent = current.parent
            if (current != node) current.recycle()
            current = parent
        }

        // If no clickable parent, use gesture to tap the node's bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()

        performTap(x, y, onComplete)
    }

    /**
     * Perform a tap gesture at the specified coordinates.
     */
    fun performTap(x: Float, y: Float, onComplete: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
                onComplete?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "Tap cancelled at ($x, $y)")
                onComplete?.invoke()
            }
        }, null)
    }

    /**
     * Perform a swipe gesture.
     */
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300, onComplete: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed")
                onComplete?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "Swipe cancelled")
                onComplete?.invoke()
            }
        }, null)
    }

    /**
     * Check if Zoom is currently in the foreground.
     */
    fun isZoomInForeground(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val packageName = rootNode.packageName?.toString()
        rootNode.recycle()
        return packageName in ZOOM_PACKAGES
    }

    /**
     * Take a screenshot and extract meeting ID using OCR.
     * This is a manual trigger - user should have the Meeting info dialog open.
     */
    fun extractMeetingIdFromScreenshot() {
        Log.d(TAG, "Manual screenshot OCR triggered")
        onDetectionStarted?.invoke()

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                applicationContext.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        Log.d(TAG, "Manual screenshot taken successfully")
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        if (bitmap != null) {
                            processScreenshotForMeetingIdWithCallback(bitmap)
                            screenshot.hardwareBuffer.close()
                        } else {
                            Log.e(TAG, "Failed to convert screenshot to bitmap")
                            onDetectionFailed?.invoke("Failed to process screenshot")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Manual screenshot failed with error code: $errorCode")
                        onDetectionFailed?.invoke("Screenshot failed (error $errorCode)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Screenshot permission denied: ${e.message}")
            onDetectionFailed?.invoke("Screenshot permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot error: ${e.message}")
            onDetectionFailed?.invoke("Screenshot error: ${e.message}")
        }
    }

    private fun processScreenshotForMeetingIdWithCallback(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                Log.e(TAG, "Manual OCR full text: ${visionText.text}")

                // Pattern to find meeting ID: 3 digits, optional separator, 3-4 digits, optional separator, 3-4 digits
                val meetingIdPattern = Regex("\\d{3}[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}")
                val fullText = visionText.text

                Log.e(TAG, "Searching for pattern in text of length ${fullText.length}")

                // First look for "Meeting ID" label followed by the number (handles newlines)
                val meetingIdLabelPattern = Regex("Meeting\\s*ID[:\\s\\n]*([\\d\\s-]+)", RegexOption.IGNORE_CASE)
                val labelMatch = meetingIdLabelPattern.find(fullText)
                Log.e(TAG, "Label match result: $labelMatch")
                if (labelMatch != null) {
                    val potentialId = labelMatch.groupValues[1].replace(Regex("[\\s-]"), "")
                    Log.e(TAG, "Potential ID from label: '$potentialId' (length ${potentialId.length})")
                    if (potentialId.length in 9..11 && potentialId.all { it.isDigit() }) {
                        Log.e(TAG, "Found Meeting ID from manual OCR (with label): $potentialId")
                        _extractedMeetingId.value = potentialId
                        onDetectionSuccess?.invoke(potentialId)
                        return@addOnSuccessListener
                    }
                }

                // Fallback: look for any meeting ID pattern
                val match = meetingIdPattern.find(fullText)
                Log.e(TAG, "Pattern match result: $match")
                if (match != null) {
                    val meetingId = match.value.replace(Regex("[\\s-]"), "")
                    Log.e(TAG, "Found Meeting ID from manual OCR (pattern match): $meetingId")
                    _extractedMeetingId.value = meetingId
                    onDetectionSuccess?.invoke(meetingId)
                } else {
                    Log.e(TAG, "Could not find Meeting ID in manual OCR text")
                    onDetectionFailed?.invoke("No Meeting ID found. Make sure Meeting info dialog is visible.")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Manual OCR failed: ${e.message}")
                onDetectionFailed?.invoke("OCR failed: ${e.message}")
            }
    }
}
