package com.thltechnologies.ussd_service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
import java.util.Locale

class UssdAccessibilityService : AccessibilityService() {
    
    companion object {
        private var instance: UssdAccessibilityService? = null
        private var pendingMessages: ArrayDeque<String> = ArrayDeque()
        var hideDialogs = false
        private var lastUssdMessage: String? = null
        private var currentStepIndex = 0
        private var retryCount = 0
        private var isDialogReady = false
        private var lastDialogDetectedTime = 0L
        private var wasInUssdWindow = false
        
        // Optimized retries with faster response when dialog is detected
        private const val MAX_RETRIES = 10
        private const val INITIAL_RETRY_DELAY_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 1000L
        private const val DIALOG_STABILITY_DELAY_MS = 400L  // Wait for dialog to stabilize
        private const val POST_CLICK_DELAY_MS = 250L  // Delay after clicking confirm
        
        // Packages that can display USSD dialogs
        private val USSD_PACKAGES = setOf(
            "com.android.phone",
            "com.samsung.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.sec.android.app.telephonyui",
            "com.huawei.systemmanager",
            "com.miui.securitycenter",
            "com.coloros.phonemanager",
            "com.oppo.usercenter",
            "com.asus.ussd",
            "com.lge.phoneui",
            "com.sonymobile.android.phone"
        )
        
        // List of common confirm button texts in multiple languages
        private val CONFIRM_BUTTON_TEXTS = listOf(
            "send", "ok", "submit", "yes", "confirm", "continue", "reply",
            "envoyer", "confirmer", "oui", "valider", "continuer", "répondre",
            "enviar", "aceptar", "sí", "confirmar",
            "senden", "ja", "bestätigen", "antworten"
        )
        
        // Cancel button texts to avoid
        private val CANCEL_BUTTON_TEXTS = listOf(
            "cancel", "annuler", "cancelar", "abbrechen", "non", "no", "dismiss", "fermer", "close"
        )

        fun sendReply(messages: List<String>) {
            println("UssdAccessibilityService: Setting pending messages: $messages")
            pendingMessages.clear()
            pendingMessages.addAll(messages)
            retryCount = 0
            isDialogReady = false
            instance?.scheduleReplyAttempt()
        }

        fun cancelSession() {
            instance?.let { service ->
                try {
                    val rootInActiveWindow = service.rootInActiveWindow
                    rootInActiveWindow?.let { root ->
                        val cancelButton = root.findAccessibilityNodeInfosByViewId("android:id/button2")
                        val clicked = cancelButton?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                        
                        if (!clicked) {
                            service.performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        
                        cancelButton?.forEach { it.recycle() }
                        root.recycle()
                    }
                    println("UssdAccessibilityService: USSD session cancelled")
                    resetState()
                } catch (e: Exception) {
                    println("UssdAccessibilityService: Error cancelling session: ${e.message}")
                }
            }
        }
        
        fun isServiceRunning(): Boolean = instance != null
        
        fun resetLastMessage() {
            resetState()
        }
        
        private fun resetState() {
            lastUssdMessage = null
            currentStepIndex = 0
            retryCount = 0
            isDialogReady = false
            lastDialogDetectedTime = 0L
            pendingMessages.clear()
        }
        
        fun notifyDialogReady() {
            isDialogReady = true
            lastDialogDetectedTime = System.currentTimeMillis()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    
    private fun getRetryDelay(): Long {
        val delay = INITIAL_RETRY_DELAY_MS + (retryCount * 200L)
        return minOf(delay, MAX_RETRY_DELAY_MS)
    }
    
    private fun scheduleReplyAttempt() {
        val delay = getRetryDelay()
        println("UssdAccessibilityService: Scheduling reply attempt in ${delay}ms")
        handler.postDelayed({
            tryPerformReply()
        }, delay)
    }

    private fun tryPerformReply() {
        if (pendingMessages.isEmpty()) {
            retryCount = 0
            println("UssdAccessibilityService: No pending messages to send")
            return
        }

        val message = pendingMessages.firstOrNull() ?: return
        println("UssdAccessibilityService: Attempt ${retryCount + 1}/$MAX_RETRIES - Message: '$message'")

        val rootInActiveWindow = this.rootInActiveWindow
        if (rootInActiveWindow == null) {
            println("UssdAccessibilityService: No active window available")
            retryIfNeeded()
            return
        }
        
        try {
            if (!isUssdDialogPresent(rootInActiveWindow)) {
                println("UssdAccessibilityService: USSD dialog not detected, waiting...")
                retryIfNeeded()
                return
            }
            
            val editText = findInputField(rootInActiveWindow)
            if (editText == null) {
                println("UssdAccessibilityService: Input field not found, waiting...")
                retryIfNeeded()
                return
            }
            
            if (!isInputFieldReady(editText)) {
                println("UssdAccessibilityService: Input field not ready")
                editText.recycle()
                retryIfNeeded()
                return
            }
            
            val confirmButton = findConfirmButton(rootInActiveWindow)
            if (confirmButton == null) {
                println("UssdAccessibilityService: Confirm button not found, waiting...")
                editText.recycle()
                retryIfNeeded()
                return
            }
            
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            Thread.sleep(50)
            
            val clearBundle = Bundle()
            clearBundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
            
            Thread.sleep(30)
            
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            val setTextSuccess = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            
            if (setTextSuccess) {
                Thread.sleep(50)
                val verifiedText = verifyTextWasSet(message)
                if (!verifiedText) {
                    println("UssdAccessibilityService: Text verification failed, retrying...")
                    editText.recycle()
                    confirmButton.recycle()
                    retryIfNeeded()
                    return
                }
            } else {
                println("UssdAccessibilityService: Failed to set text, retrying...")
                editText.recycle()
                confirmButton.recycle()
                retryIfNeeded()
                return
            }
            
            editText.recycle()
            
            pendingMessages.removeFirstOrNull()
            currentStepIndex++
            retryCount = 0
            isDialogReady = false
            
            handler.postDelayed({
                clickConfirmButtonWithRetry(confirmButton)
            }, POST_CLICK_DELAY_MS)
            
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error in tryPerformReply: ${e.message}")
            e.printStackTrace()
            retryIfNeeded()
        } finally {
            try {
                rootInActiveWindow.recycle()
            } catch (e: Exception) {
                // Already recycled
            }
        }
    }
    
    private fun isUssdDialogPresent(root: AccessibilityNodeInfo): Boolean {
        val hasEditText = findInputField(root) != null
        val hasButtons = findNodesByClassName(root, "android.widget.Button").isNotEmpty()
        val hasTextView = findNodesByClassName(root, "android.widget.TextView").isNotEmpty()
        return hasEditText && hasButtons && hasTextView
    }
    
    private fun isInputFieldReady(editText: AccessibilityNodeInfo): Boolean {
        return editText.isVisibleToUser && editText.isEnabled
    }
    
    private fun verifyTextWasSet(expectedText: String): Boolean {
        val root = this.rootInActiveWindow ?: return false
        try {
            val editText = findInputField(root)
            if (editText != null) {
                val actualText = editText.text?.toString() ?: ""
                val matches = actualText == expectedText
                editText.recycle()
                return matches
            }
        } finally {
            root.recycle()
        }
        return false
    }
    
    private fun retryIfNeeded() {
        retryCount++
        if (retryCount < MAX_RETRIES) {
            val delay = getRetryDelay()
            scheduleReplyAttempt()
        } else {
            val skippedMessage = pendingMessages.removeFirstOrNull()
            println("UssdAccessibilityService: Max retries reached, skipped: '$skippedMessage'")
            retryCount = 0
            isDialogReady = false
            if (pendingMessages.isNotEmpty()) {
                scheduleReplyAttempt()
            }
        }
    }
    
    private fun clickConfirmButtonWithRetry(button: AccessibilityNodeInfo, attempt: Int = 1) {
        val maxClickAttempts = 3
        try {
            val clickSuccess = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clickSuccess && attempt < maxClickAttempts) {
                handler.postDelayed({
                    tryAlternativeClick(button, attempt + 1)
                }, 300)
            } else {
                button.recycle()
            }
        } catch (e: Exception) {
            button.recycle()
        }
    }
    
    private fun tryAlternativeClick(button: AccessibilityNodeInfo, attempt: Int) {
        val root = this.rootInActiveWindow
        if (root != null) {
            try {
                val freshButton = findConfirmButton(root)
                if (freshButton != null) {
                    clickConfirmButtonWithRetry(freshButton, attempt)
                } else {
                    tryAlternativeConfirmMethods(root)
                }
            } finally {
                root.recycle()
            }
        }
        try {
            button.recycle()
        } catch (e: Exception) {
            // Already recycled
        }
    }
 
    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editTexts = findNodesByClassName(root, "android.widget.EditText")
        val result = editTexts.firstOrNull()
        editTexts.filter { it != result }.forEach { it.recycle() }
        return result
    }
 
    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = findNodesByClassName(root, "android.widget.Button")
        val confirmButton = buttons.firstOrNull { button ->
            val buttonText = button.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            CONFIRM_BUTTON_TEXTS.any { confirmText -> buttonText.contains(confirmText) }
        }
        buttons.filter { it != confirmButton }.forEach { it.recycle() }
        return confirmButton
    }
 
    private fun tryAlternativeConfirmMethods(root: AccessibilityNodeInfo) {
        val allButtons = findNodesByClassName(root, "android.widget.Button")
        val sortedButtons = allButtons.sortedByDescending { button ->
            val buttonText = button.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            when {
                CONFIRM_BUTTON_TEXTS.any { buttonText.contains(it) } -> 2
                CANCEL_BUTTON_TEXTS.any { buttonText.contains(it) } -> 0
                else -> 1
            }
        }
        
        for (button in sortedButtons) {
            val buttonText = button.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (CANCEL_BUTTON_TEXTS.any { buttonText.contains(it) }) {
                continue
            }
            val clickSuccess = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clickSuccess) {
                allButtons.forEach { it.recycle() }
                return
            }
        }
        allButtons.forEach { it.recycle() }
        tryClickByViewId(root)
    }
    
    private fun tryClickByViewId(root: AccessibilityNodeInfo) {
        val buttonIds = listOf(
            "android:id/button1",
            "android:id/button3",
            "com.android.phone:id/send_button",
            "com.android.phone:id/button_send"
        )
        for (buttonId in buttonIds) {
            val buttons = root.findAccessibilityNodeInfosByViewId(buttonId)
            val button = buttons?.firstOrNull()
            if (button != null) {
                val clickSuccess = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickSuccess) {
                    buttons.forEach { it.recycle() }
                    return
                }
                buttons.forEach { it.recycle() }
            }
        }
    }
 
    private fun findNodesByClassName(root: AccessibilityNodeInfo?, className: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return result
 
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
 
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.toString() == className) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }
 
    private fun isUssdPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        return USSD_PACKAGES.any { ussdPkg -> 
            packageName.lowercase(Locale.ROOT).contains(ussdPkg.lowercase(Locale.ROOT)) 
        } || packageName.lowercase(Locale.ROOT).contains("phone") 
          || packageName.lowercase(Locale.ROOT).contains("dialer")
          || packageName.lowercase(Locale.ROOT).contains("telecom")
    }
 
    private fun isValidUssdMessage(message: String): Boolean {
        val lowerMessage = message.lowercase(Locale.ROOT)
        if (message.length < 3) return false
        val invalidPatterns = listOf(
            "play store", "google play", "raccourci", "shortcut",
            "services téléchargés", "downloaded services", 
            "volume", "settings", "paramètres",
            "notification", "battery", "batterie",
            "wifi", "bluetooth", "airplane", "avion"
        )
        if (invalidPatterns.any { lowerMessage.contains(it) }) {
            return false
        }
        return true
    }
 
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString()
        if (!isUssdPackage(packageName)) {
            return
        }
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && hideDialogs) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
 
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleDialogAppeared(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleDialogContentChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    checkAndTriggerReply()
                }
            }
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error in onAccessibilityEvent: ${e.message}")
        }
    }
    
    private fun handleDialogAppeared(event: AccessibilityEvent) {
        val nodeInfo = event.source ?: return
        try {
            val root = this.rootInActiveWindow
            if (root != null) {
                val hasInputField = findInputField(root) != null
                val hasButtons = findNodesByClassName(root, "android.widget.Button").isNotEmpty()
                val hasTextView = findNodesByClassName(root, "android.widget.TextView").isNotEmpty()
                
                if (hasButtons && hasTextView) {
                    extractAndSendDialogContent(root)
                    
                    if (hasInputField && pendingMessages.isNotEmpty()) {
                        notifyDialogReady()
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed({
                            retryCount = 0
                            tryPerformReply()
                        }, DIALOG_STABILITY_DELAY_MS)
                    }
                }
                root.recycle()
            }
        } finally {
            nodeInfo.recycle()
        }
    }
    
    private fun handleDialogContentChanged(event: AccessibilityEvent) {
        val nodeInfo = event.source ?: return
        try {
            val root = this.rootInActiveWindow
            if (root != null) {
                val hasInputField = findInputField(root) != null
                val hasButtons = findNodesByClassName(root, "android.widget.Button").isNotEmpty()
                val hasTextView = findNodesByClassName(root, "android.widget.TextView").isNotEmpty()
                
                if (hasButtons && hasTextView) {
                    extractAndSendDialogContent(root)
                    if (hasInputField && pendingMessages.isNotEmpty() && !isDialogReady) {
                        notifyDialogReady()
                        checkAndTriggerReply()
                    }
                }
                root.recycle()
            }
        } finally {
            nodeInfo.recycle()
        }
    }
    
    private fun extractAndSendDialogContent(root: AccessibilityNodeInfo) {
        val dialogContent = getCompleteDialogContent(root)
        if (dialogContent != null && dialogContent.isNotBlank()) {
            if (isValidUssdMessage(dialogContent) && dialogContent != lastUssdMessage) {
                lastUssdMessage = dialogContent
                UssdServicePlugin.onUssdResult(dialogContent)
            }
        }
    }
    
    private fun checkAndTriggerReply() {
        if (pendingMessages.isEmpty()) {
            return
        }
        if (retryCount == 0 && isDialogReady) {
            val timeSinceDialogDetected = System.currentTimeMillis() - lastDialogDetectedTime
            if (timeSinceDialogDetected >= DIALOG_STABILITY_DELAY_MS) {
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    tryPerformReply()
                }
            } else {
                val remainingDelay = DIALOG_STABILITY_DELAY_MS - timeSinceDialogDetected
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    tryPerformReply()
                }, remainingDelay)
            }
        }
    }
 
    private fun getCompleteDialogContent(node: AccessibilityNodeInfo): String? {
        val allTexts = mutableListOf<String>()
        collectAllTextViewContent(node, allTexts)
        
        if (allTexts.isEmpty()) return null
        
        val meaningfulTexts = allTexts.filter { text ->
            val lower = text.lowercase(Locale.ROOT)
            !CONFIRM_BUTTON_TEXTS.contains(lower) &&
            lower != "annuler" && lower != "cancel" &&
            lower != "message ussd" && lower != "ussd code" &&
            text.length > 2
        }
        
        return if (meaningfulTexts.isNotEmpty()) {
            meaningfulTexts.joinToString("\n").trim()
        } else {
            null
        }
    }
    
    private fun collectAllTextViewContent(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        if (node.className?.toString() == "android.widget.EditText") {
            return
        }
        if (node.className?.toString() == "android.widget.TextView" && node.text != null) {
            val text = node.text.toString().trim()
            if (text.isNotBlank() && text.length > 1) {
                texts.add(text)
            }
        }
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i) ?: continue
            try {
                collectAllTextViewContent(childNode, texts)
            } finally {
                childNode.recycle()
            }
        }
    }
 
    override fun onInterrupt() {
        println("UssdAccessibilityService: Service interrupted")
    }
 
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        println("UssdAccessibilityService: Service connected")
    }
 
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pendingMessages.clear()
        lastUssdMessage = null
        currentStepIndex = 0
        retryCount = 0
        handler.removeCallbacksAndMessages(null)
        println("UssdAccessibilityService: Service destroyed")
    }
}
