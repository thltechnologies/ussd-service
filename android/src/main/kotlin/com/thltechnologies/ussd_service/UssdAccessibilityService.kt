package com.thltechnologies.ussd_service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.util.Locale

class UssdAccessibilityService : AccessibilityService() {
    
    companion object {
        private var instance: UssdAccessibilityService? = null
        private var pendingMessages: ArrayDeque<String> = ArrayDeque()
        var hideDialogs = false
        var customTerminalKeywords: List<String> = emptyList()
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
                    service.dismissCurrentDialog()
                    println("UssdAccessibilityService: USSD session cancelled and dialog dismissed")
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
            wasInUssdWindow = false
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
 
    private fun findInputField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            val isEdit = node.isEditable ||
                    className.contains("EditText", ignoreCase = true) ||
                    className.contains("AutoCompleteTextView", ignoreCase = true) ||
                    (node.actionList != null && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })

            if (isEdit) {
                while (queue.isNotEmpty()) {
                    val rem = queue.removeFirst()
                    if (rem != node && rem != root) {
                        try { rem.recycle() } catch (e: Exception) {}
                    }
                }
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
 
    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = findButtons(root)
        val confirmButton = buttons.firstOrNull { button ->
            val buttonText = button.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            CONFIRM_BUTTON_TEXTS.any { confirmText -> buttonText.contains(confirmText) }
        }
        buttons.filter { it != confirmButton }.forEach { it.recycle() }
        return confirmButton
    }

    private fun findButtons(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (root == null) return result
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            if (node.isClickable && (className.contains("Button", ignoreCase = true) || className.contains("TextView", ignoreCase = true))) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }
 
    private fun tryAlternativeConfirmMethods(root: AccessibilityNodeInfo) {
        val allButtons = findButtons(root)
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
            val nodeClass = node.className?.toString() ?: ""
            if (nodeClass.contains(className, ignoreCase = true)) {
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
        if (packageName.equals(applicationContext.packageName, ignoreCase = true)) return false
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

    private fun getInstalledKeyboardPackages(): Set<String> {
        val packages = mutableSetOf<String>()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.forEach { ime ->
                packages.add(ime.packageName.lowercase(Locale.ROOT))
            }
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error querying InputMethodManager: ${e.message}")
        }
        return packages
    }

    private fun isKeyboardOrSystemPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        val lower = packageName.lowercase(Locale.ROOT)

        // 1. Vérification dynamique de TOUS les claviers officiellement installés sur le téléphone Android
        val installedImePackages = getInstalledKeyboardPackages()
        if (installedImePackages.contains(lower) || installedImePackages.any { lower.contains(it) }) {
            return true
        }

        // 2. Mots-clés universels et génériques pour tous les claviers tiers (FlorisBoard, AnySoftKeyboard, Fleksy, etc.)
        val genericKeyboardKeywords = listOf(
            "inputmethod", "keyboard", "honeyboard", "swiftkey", "ime",
            "gboard", "fleksy", "typewise", "openboard", "anysoftkeyboard",
            "kika", "facemoji", "touchpal", "florisboard", "board",
            "systemui", "autofill", "input", "latin", "samsunganalytics"
        )
        return genericKeyboardKeywords.any { lower.contains(it) }
    }

    private fun isUssdWindowStillActive(): Boolean {
        try {
            val currentRoot = this.rootInActiveWindow
            if (currentRoot != null) {
                val pkg = currentRoot.packageName?.toString()
                val isUssd = isUssdPackage(pkg) || findInputField(currentRoot) != null
                try { currentRoot.recycle() } catch (e: Exception) {}
                if (isUssd) return true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (window in this.windows) {
                    val root = window.root
                    if (root != null) {
                        val pkg = root.packageName?.toString()
                        val isUssd = isUssdPackage(pkg) || findInputField(root) != null
                        try { root.recycle() } catch (e: Exception) {}
                        if (isUssd) return true
                    }
                }
            }
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error checking isUssdWindowStillActive: ${e.message}")
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString()

        // Ignore keyboard/IME or system overlay events to prevent false session closure while user is typing
        if (isKeyboardOrSystemPackage(packageName)) {
            return
        }

        if (!isUssdPackage(packageName)) {
            if (wasInUssdWindow && packageName != null) {
                // Vérifier si la fenêtre USSD (ou son champ de saisie) est toujours présente à l'écran
                if (isUssdWindowStillActive()) {
                    return
                }

                wasInUssdWindow = false
                println("UssdAccessibilityService: USSD window closed, transitioned to $packageName")
                handler.postDelayed({
                    UssdServicePlugin.onUssdResult("SESSION_COMPLETED")
                }, 300L)
            }
            return
        }

        wasInUssdWindow = true

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

    private var isDismissingDialog = false

    private fun bringAppToFront() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launchIntent)
                println("UssdAccessibilityService: Brought Flutter host app to front")
            }
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error bringing app to front: ${e.message}")
        }
    }

    fun dismissCurrentDialog(providedRoot: AccessibilityNodeInfo? = null) {
        if (isDismissingDialog) {
            println("UssdAccessibilityService: Dismiss already in progress, ignoring duplicate call")
            return
        }
        val root = providedRoot ?: this.rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString()
            if (!isUssdPackage(pkg)) {
                println("UssdAccessibilityService: Active window ($pkg) is not a USSD dialog, skipping dismiss")
                return
            }

            isDismissingDialog = true
            handler.postDelayed({ isDismissingDialog = false }, 1200L)

            // 1. Try finding and clicking standard dialog buttons (button1 = positive/OK, button2 = negative/Cancel, button3 = neutral)
            val buttonIds = listOf(
                "android:id/button1",
                "android:id/button2",
                "android:id/button3",
                "com.android.phone:id/button_ok",
                "com.android.phone:id/ok_button",
                "com.android.phone:id/dialog_button",
                "com.samsung.android.phone:id/button1",
                "com.samsung.android.phone:id/button2"
            )
            var clicked = false
            for (id in buttonIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable && node.isVisibleToUser) {
                            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (clicked) {
                                println("UssdAccessibilityService: Clicked dialog button by ID: $id")
                                break
                            }
                        }
                    }
                    nodes.forEach { it.recycle() }
                    if (clicked) break
                }
            }

            // 2. If not clicked by ID, find buttons by text (OK, Fermer, etc.)
            if (!clicked) {
                val allButtons = findNodesByClassName(root, "android.widget.Button")
                for (btn in allButtons) {
                    val txt = btn.text?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
                    if (txt == "ok" || txt == "fermer" || txt == "close" || txt == "annuler" || txt == "cancel" || txt == "terminer" || txt == "dismiss" || txt == "valider") {
                        clicked = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            println("UssdAccessibilityService: Clicked dialog button by text: '$txt'")
                            break
                        }
                    }
                }
                allButtons.forEach { it.recycle() }
            }

            // 3. ONLY if clicking the button failed, fallback to GLOBAL_ACTION_BACK on the USSD window
            if (!clicked) {
                val currentActive = this.rootInActiveWindow
                if (currentActive != null && isUssdPackage(currentActive.packageName?.toString())) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    println("UssdAccessibilityService: Dispatched GLOBAL_ACTION_BACK fallback to dismiss USSD dialog")
                    currentActive.recycle()
                }
            }

            // Bring the Flutter host app back to the front so the custom result dialog is shown
            bringAppToFront()
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error dismissing dialog: ${e.message}")
        } finally {
            if (providedRoot == null) {
                try { root.recycle() } catch (e: Exception) {}
            }
        }
    }

    private fun isPromptDialogContent(message: String?): Boolean {
        if (message == null || message.isBlank()) return false
        val lower = message.lowercase(Locale.ROOT).trim()

        // Explicit completion indicators
        val explicitCompletedPhrases = listOf(
            "retrait effectue", "retrait effectué", "retrait reussi", "retrait réussi",
            "depot effectue", "dépôt effectué", "depot reussi", "dépôt réussi",
            "transfert effectue", "transfert effectué", "transfert reussi", "transfert réussi",
            "operation effectuee", "opération effectuée", "operation reussie", "opération réussie",
            "transaction effectuee", "transaction effectuée", "transaction reussie", "transaction réussie",
            "paiement effectue", "paiement effectué", "recharge effectuee", "recharge effectuée",
            "vous avez envoye", "vous avez envoyé", "vous avez recu", "vous avez reçu",
            "details de la transaction", "détails de la transaction",
            "merci d avoir utilise", "merci d'avoir utilise", "merci d'avoir utilisé"
        )
        if (explicitCompletedPhrases.any { lower.contains(it) } &&
            !lower.contains("saisir") && !lower.contains("ressaisir") && !lower.contains("resaisir") &&
            !lower.contains("entrez") && !lower.contains("entrer le") && !lower.contains("tapez")) {
            return false
        }

        val promptKeywords = listOf(
            "saisir", "ressaisir", "resaisir", "re-saisir", "saisie",
            "entrer", "entrez", "rentrer", "reentrer", "re-entrer",
            "entrer une derniere fois", "entrer une dernière fois", "entrer une derniere", "entrer une dernière",
            "verifier et entrer", "vérifier et entrer", "veuillez verifier", "veuillez vérifier",
            "numero du client", "numéro du client", "numero du destinataire", "numéro du destinataire",
            "pour confirmer", "pour confirmer l'operation", "pour confirmer l'opération",
            "repeter", "répéter", "confirmer", "confirmation", "valider", "validation",
            "veuillez", "taper", "tapez", "repondre", "répondre", "repondez", "répondez",
            "choix", "choisir", "choisissez", "selectionner", "sélectionner",
            "mot de passe", "code pin", "code secret", "destinataire", "beneficiaire", "bénéficiaire"
        )
        if (promptKeywords.any { lower.contains(it) }) return true
        if (lower.contains(":") && !lower.contains("sms") && !lower.contains("solde:") && !lower.contains("ref:")) return true
        if (lower.endsWith("?")) return true
        if (Regex("""^\d+[\.:\)]""").containsMatchIn(lower)) return true
        return false
    }

    private fun isTerminalDialogContent(message: String?): Boolean {
        if (message == null || message.isBlank()) return false
        val lower = message.lowercase(Locale.ROOT).trim()

        if (customTerminalKeywords.isNotEmpty()) {
            if (customTerminalKeywords.any { lower.contains(it.lowercase(Locale.ROOT).trim()) }) {
                return !isPromptDialogContent(message)
            }
        }

        if (isPromptDialogContent(message)) {
            return false
        }

        val finalKeywords = listOf(
            "depot effectue", "dépôt effectué", "depot reussi", "dépôt réussi", "depot confirme", "dépôt confirmé", "depot valide", "dépôt validé",
            "transfert effectue", "transfert effectué", "transfert reussi", "transfert réussi", "transfert confirme", "transfert confirmé",
            "retrait effectue", "retrait effectué", "retrait reussi", "retrait réussi",
            "paiement effectue", "paiement effectué", "paiement reussi", "paiement réussi",
            "achat effectue", "achat effectué", "recharge effectuee", "recharge effectuée", "recharge reussie", "recharge réussie",
            "operation effectuee", "opération effectuée", "operation reussie", "opération réussie",
            "transaction effectuee", "transaction effectuée", "transaction reussie", "transaction réussie", "transaction terminee", "transaction terminée",
            "details de la transaction", "détails de la transaction", "sms de confirmation",
            "merci d avoir utilise", "merci d'avoir utilise", "merci d'avoir utilisé",
            "merci d'utiliser", "merci pour votre confiance", "merci de votre fidelite", "merci de votre fidélité",
            "solde", "nouveau solde", "solde actuel", "votre solde", "solde restant", "solde:",
            "compte credite", "compte crédité", "compte debite", "compte débité",
            "vous avez envoye", "vous avez envoyé", "vous avez recu", "vous avez reçu",
            "reussi avec succes", "réussi avec succès", "effectue avec succes", "effectué avec succès",
            "id trans", "id transaction",
            "echec", "échec", "erreur", "invalide", "rejetee", "rejetée", "operation impossible", "service indisponible",
            "solde insuffisant", "solde indisponible", "fonds insuffisants",
            "code secret incorrect", "code pin incorrect", "mot de passe incorrect", "pin incorrect", "code incorrect",
            "compte bloque", "compte bloqué", "compte verrouille", "compte verrouillé",
            "numero incorrect", "numéro incorrect", "numero non valide", "numéro non valide",
            "transaction annulee", "transaction annulée", "session annulee", "session annulée", "session expiree", "session expirée", "delai depasse", "délai dépassé"
        )
        return finalKeywords.any { lower.contains(it) }
    }

    private fun handleDialogAppeared(event: AccessibilityEvent) {
        val nodeInfo = event.source ?: return
        try {
            val root = this.rootInActiveWindow ?: nodeInfo
            val hasInputField = findInputField(root) != null
            val hasButtons = findNodesByClassName(root, "android.widget.Button").isNotEmpty()
            val hasTextView = findNodesByClassName(root, "android.widget.TextView").isNotEmpty()
            
            if (hasTextView || hasButtons || hasInputField) {
                val dialogContent = extractAndSendDialogContent(root)
                
                if (hasInputField && pendingMessages.isNotEmpty()) {
                    notifyDialogReady()
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        retryCount = 0
                        tryPerformReply()
                    }, DIALOG_STABILITY_DELAY_MS)
                } else if (hideDialogs && pendingMessages.isEmpty() && !hasInputField && isTerminalDialogContent(dialogContent)) {
                    // This is a TRUE final receipt dialog! Auto-dismiss immediately and finish session.
                    println("UssdAccessibilityService: Final terminal dialog confirmed ('$dialogContent'). Dismissing in 100ms...")
                    handler.postDelayed({
                        dismissCurrentDialog()
                        UssdServicePlugin.onUssdResult("SESSION_COMPLETED")
                    }, 100L)
                } else {
                    println("UssdAccessibilityService: Interactive prompt or intermediate screen. Keeping open for user input.")
                }
            }
            if (root != nodeInfo) {
                root.recycle()
            }
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error in handleDialogAppeared: ${e.message}")
        } finally {
            try {
                nodeInfo.recycle()
            } catch (e: Exception) {}
        }
    }
    
    private fun handleDialogContentChanged(event: AccessibilityEvent) {
        val nodeInfo = event.source ?: return
        try {
            val root = this.rootInActiveWindow ?: nodeInfo
            val hasInputField = findInputField(root) != null
            val hasButtons = findNodesByClassName(root, "android.widget.Button").isNotEmpty()
            val hasTextView = findNodesByClassName(root, "android.widget.TextView").isNotEmpty()
            
            if (hasTextView || hasButtons || hasInputField) {
                val dialogContent = extractAndSendDialogContent(root)
                if (hasInputField && pendingMessages.isNotEmpty() && !isDialogReady) {
                    notifyDialogReady()
                    checkAndTriggerReply()
                } else if (hideDialogs && pendingMessages.isEmpty() && !hasInputField && isTerminalDialogContent(dialogContent)) {
                    // Content updated with terminal receipt! Auto-dismiss immediately and finish session.
                    println("UssdAccessibilityService: Terminal content updated ('$dialogContent'). Dismissing in 100ms...")
                    handler.postDelayed({
                        dismissCurrentDialog()
                        UssdServicePlugin.onUssdResult("SESSION_COMPLETED")
                    }, 100L)
                }
            }
            if (root != nodeInfo) {
                root.recycle()
            }
        } catch (e: Exception) {
            println("UssdAccessibilityService: Error in handleDialogContentChanged: ${e.message}")
        } finally {
            try {
                nodeInfo.recycle()
            } catch (e: Exception) {}
        }
    }
    
    private fun extractAndSendDialogContent(root: AccessibilityNodeInfo): String? {
        val dialogContent = getCompleteDialogContent(root)
        if (dialogContent != null && dialogContent.isNotBlank()) {
            if (isValidUssdMessage(dialogContent) && dialogContent != lastUssdMessage) {
                lastUssdMessage = dialogContent
                val hasInput = findInputField(root) != null
                if (hasInput) {
                    UssdServicePlugin.onUssdResult("PROMPT_DIALOG:$dialogContent")
                } else {
                    UssdServicePlugin.onUssdResult(dialogContent)
                }
            }
        }
        return dialogContent
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
