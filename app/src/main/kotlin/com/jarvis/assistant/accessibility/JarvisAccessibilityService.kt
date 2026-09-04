package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Lets JARVIS operate other apps on the user's behalf: tapping visible buttons,
 * typing into visible fields, going back/home, scrolling — the same things a
 * sighted user could do by touching the screen. This service does nothing unless
 * the user has explicitly turned it on for JARVIS in Settings > Accessibility,
 * and it only ever acts on a small list of steps JARVIS itself enqueued in direct
 * response to something the user asked for — it never acts on its own.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var attemptsOnCurrentStep = 0
    private val pollRunnable = object : Runnable {
        override fun run() {
            tryRunNextStep()
            if (queue.isNotEmpty()) handler.postDelayed(this, 400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (queue.isNotEmpty()) tryRunNextStep()
    }

    override fun onInterrupt() { /* no-op */ }

    private fun tryRunNextStep() {
        val step = queue.peek() ?: return
        val root = rootInActiveWindow
        val label = describeStep(step)
        if (attemptsOnCurrentStep == 0) {
            onStepEvent?.invoke(completedSteps + 1, totalSteps, label, null)
        }
        val handled = try {
            when (step) {
                is AutomationStep.TapText -> root?.let { tapNode(findByText(it, step.text)) } ?: false
                is AutomationStep.TapDescription -> root?.let { tapNode(findByDescription(it, step.description)) } ?: false
                is AutomationStep.TypeText -> root?.let { typeInto(it, step.text) } ?: false
                is AutomationStep.Wait -> true
                AutomationStep.PressBack -> performGlobalAction(GLOBAL_ACTION_BACK)
                AutomationStep.PressHome -> performGlobalAction(GLOBAL_ACTION_HOME)
                AutomationStep.ScrollForward -> root?.let { scroll(it, forward = true) } ?: false
                AutomationStep.ScrollBackward -> root?.let { scroll(it, forward = false) } ?: false
                is AutomationStep.LongPressText -> root?.let { longPressNode(findByText(it, step.text)) } ?: false
                is AutomationStep.LongPressDescription -> root?.let { longPressNode(findByDescription(it, step.description)) } ?: false
                AutomationStep.SubmitField -> root?.let { submitFocusedField(it) } ?: false
                AutomationStep.TapFirstResult -> root?.let { tapNode(findFirstResultNode(it)) } ?: false
            }
        } catch (e: Exception) {
            false
        }

        if (handled) {
            queue.poll()
            completedSteps++
            onStepEvent?.invoke(completedSteps, totalSteps, label, true)
            attemptsOnCurrentStep = 0
            val delay = if (step is AutomationStep.Wait) step.milliseconds else 250L
            handler.postDelayed({ tryRunNextStep() }, delay)
        } else {
            attemptsOnCurrentStep++
            if (attemptsOnCurrentStep > MAX_ATTEMPTS_PER_STEP) {
                // Give up on this one step so the whole automation doesn't hang forever;
                // move on so the rest of the sequence still gets a chance to run.
                queue.poll()
                lastAutomationFailed = true
                completedSteps++
                onStepEvent?.invoke(completedSteps, totalSteps, label, false)
                attemptsOnCurrentStep = 0
            }
        }
    }

    private fun describeStep(step: AutomationStep): String = when (step) {
        is AutomationStep.TapText -> "TAP \"${step.text}\""
        is AutomationStep.TapDescription -> "TAP \"${step.description}\""
        is AutomationStep.TypeText -> "TYPE \"${step.text}\""
        is AutomationStep.Wait -> "WAIT"
        AutomationStep.PressBack -> "BACK"
        AutomationStep.PressHome -> "HOME"
        AutomationStep.ScrollForward -> "SCROLL"
        AutomationStep.ScrollBackward -> "SCROLL"
        is AutomationStep.LongPressText -> "LONG_PRESS \"${step.text}\""
        is AutomationStep.LongPressDescription -> "LONG_PRESS \"${step.description}\""
        AutomationStep.SubmitField -> "SUBMIT"
        AutomationStep.TapFirstResult -> "TAP FIRST RESULT"
    }

    private fun tapNode(node: AccessibilityNodeInfo?): Boolean {
        var target = node ?: return false
        var hops = 0
        while (!target.isClickable && target.parent != null && hops < 8) {
            target = target.parent
            hops++
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun longPressNode(node: AccessibilityNodeInfo?): Boolean {
        var target = node ?: return false
        var hops = 0
        while (!target.isLongClickable && target.parent != null && hops < 8) {
            target = target.parent
            hops++
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    /** Submits whatever field currently has input focus — the standard way to trigger a search
     * or send a typed message once the text is already in the field. Falls back gracefully
     * across API levels since ACTION_IME_ENTER only exists from Android 11 (API 30) onward. */
    private fun submitFocusedField(root: AccessibilityNodeInfo): Boolean {
        val field = findFocusedEditable(root) ?: findFirstEditable(root) ?: return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (field.actionList.contains(imeEnter) && field.performAction(imeEnter.id)) return true
        }
        // Fallback: many search UIs expose a "Search"/"Go"/magnifier button next to the field.
        val root2 = rootInActiveWindow ?: root
        val searchButton = findByDescription(root2, "search") ?: findByText(root2, "search")
        return searchButton?.let { tapNode(it) } ?: false
    }

    /**
     * Heuristic used for "pehli video chalao" / "upar wala kholo" / "select the first one" style
     * commands: picks the first clickable, meaningfully-labelled node below the top app-bar area
     * of the screen (skipping the small cluster of nav/toolbar icons at the very top so we land
     * on actual result content rather than a menu or back button).
     */
    private fun findFirstResultNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickableWithLabel(root, candidates)
        val bounds = android.graphics.Rect()
        val topBarThresholdPx = 220 // skip the toolbar/status-bar cluster of icons
        return candidates
            .filter { node ->
                node.getBoundsInScreen(bounds)
                bounds.top > topBarThresholdPx
            }
            .minByOrNull { node ->
                node.getBoundsInScreen(bounds)
                bounds.top
            }
    }

    private fun collectClickableWithLabel(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40 || out.size > 200) return
        val label = node.text?.toString()?.trim().orEmpty().ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
        if (node.isClickable && label.length > 1) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickableWithLabel(it, out, depth + 1) }
        }
    }

    /** Finds a search-like editable field on the current screen: a search icon/box, or an
     * EditText whose hint/description mentions "search". Falls back to the first editable
     * field found if nothing looks search-specific — most single-field screens are search. */
    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByDescription(root, "search")?.let { node ->
            if (node.isEditable) return node
            // A search *icon* rather than the field itself — tap it to reveal/focus the field,
            // then look again.
            tapNode(node)
        }
        val refreshedRoot = rootInActiveWindow ?: root
        findEditableByHint(refreshedRoot, "search")?.let { return it }
        return findFocusedEditable(refreshedRoot) ?: findFirstEditable(refreshedRoot)
    }

    private fun findEditableByHint(node: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        if (node.isEditable) {
            val text = (node.hintText?.toString() ?: node.contentDescription?.toString()).orEmpty()
            if (text.contains(hint, ignoreCase = true)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableByHint(child, hint)?.let { return it }
        }
        return null
    }

    private fun typeInto(root: AccessibilityNodeInfo, text: String): Boolean {
        val field = findFocusedEditable(root) ?: findFirstEditable(root) ?: return false
        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.takeIf { it.isEditable }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditable(child)?.let { return it }
        }
        return null
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        if (nodeText != null && nodeText.contains(text, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByText(child, text)?.let { return it }
        }
        return null
    }

    private fun findByDescription(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.contains(description, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByDescription(child, description)?.let { return it }
        }
        return null
    }

    private fun scroll(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return findScrollable(root)?.performAction(action) ?: false
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    companion object {
        private const val MAX_ATTEMPTS_PER_STEP = 20 // ~8s at 400ms polling
        private var instance: JarvisAccessibilityService? = null
        private val queue = ArrayDeque<AutomationStep>()
        private var totalSteps = 0
        private var completedSteps = 0
        private var lastAutomationFailed = false

        /** Fires with real progress as each queued step actually runs: (current, total, label, success). */
        var onStepEvent: ((Int, Int, String, Boolean?) -> Unit)? = null

        val isEnabled: Boolean get() = instance != null

        /** Immediate (non-queued) system navigation actions — no screen inspection needed for these. */
        fun pressBack(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        fun pressHome(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        fun openRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        /** Reads the visible text currently on screen, top to bottom, deduplicated. */
        fun readVisibleText(): String? {
            val root = instance?.rootInActiveWindow ?: return null
            val lines = mutableListOf<String>()
            collectVisibleText(root, lines)
            return lines.distinct().take(80).joinToString("\n").takeIf { it.isNotBlank() }
        }

        private fun collectVisibleText(node: AccessibilityNodeInfo, out: MutableList<String>) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) out.add(text)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collectVisibleText(it, out) }
            }
        }

        /** Cancels any in-progress multi-step automation immediately — used by the "Stop"/"Bas"
         * voice command so a running task/AUTOMATE sequence doesn't keep tapping/typing. */
        fun cancelQueue() {
            queue.clear()
            totalSteps = 0
            completedSteps = 0
        }

        /** Queues a sequence of steps to run against whatever app is currently in the foreground. */
        fun enqueue(steps: List<AutomationStep>) {
            totalSteps = steps.size
            completedSteps = 0
            lastAutomationFailed = false
            queue.addAll(steps)
            val service = instance ?: return
            service.handler.post(service.pollRunnable)
        }

        // ---------------------------------------------------------------------------------
        // Instant (non-queued) single-shot actions. These act on whatever is on screen right
        // now and return immediately — used for fast, no-confirmation commands like "scroll
        // down" that must never wait on a multi-step queue or pop open the JARVIS activity.
        // ---------------------------------------------------------------------------------

        /** Scrolls the current app's content down (forward). Acts on whatever app is foreground. */
        fun scrollDown(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            return svc.scroll(root, forward = true)
        }

        /** Scrolls the current app's content up (backward). */
        fun scrollUp(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            return svc.scroll(root, forward = false)
        }

        /** Long-presses whatever visible text/description best matches [target]. */
        fun longPress(target: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val node = svc.findByText(root, target) ?: svc.findByDescription(root, target)
            return svc.longPressNode(node)
        }

        /**
         * Finds the search field on the current screen, types [query] into it, and submits.
         * Works generically across apps (YouTube, Chrome, Play Store, etc.) rather than being
         * hardcoded to any one app's layout.
         */
        fun searchCurrentApp(query: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val field = svc.findSearchField(root) ?: return false
            if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            val typed = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!typed) return false
            // Give the field a beat to update before trying to submit it.
            val refreshedRoot = svc.rootInActiveWindow ?: root
            return svc.submitFocusedField(refreshedRoot)
        }

        /** Taps the first result-like item on the current screen ("pehli video chalao"). */
        fun tapFirstResult(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            return svc.tapNode(svc.findFirstResultNode(root))
        }

        /** Taps a visible "Send" button/icon — the second half of a user-confirmed message send.
         * Only ever called after the user has explicitly said yes to a pending send. */
        fun tapSendButton(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val node = svc.findByDescription(root, "send") ?: svc.findByText(root, "send")
            return svc.tapNode(node)
        }

        /** Package currently represented by the active accessibility window. */
        fun currentPackageName(): String? = instance?.rootInActiveWindow?.packageName?.toString()

        /** True while the service has queued automation steps. */
        fun isAutomationBusy(): Boolean = queue.isNotEmpty()
        fun lastAutomationHadFailure(): Boolean = lastAutomationFailed

        /** Compact, sanitized snapshot of the current screen for local heuristics / optional AI
         * context. Never exposes the raw accessibility tree — bounded in size and depth. */
        fun captureScreenContext(): ScreenContext? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            val elements = mutableListOf<ScreenElement>()
            collectScreenElements(root, elements)
            return ScreenContext(
                packageName = root.packageName?.toString(),
                elements = elements.take(60)
            )
        }

        private fun collectScreenElements(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>, depth: Int = 0) {
            if (depth > 40 || out.size >= 60) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if ((text.isNotEmpty() || desc.isNotEmpty()) && (node.isClickable || node.isEditable || node.isScrollable || text.isNotEmpty())) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                val role = when {
                    node.isEditable -> "text_field"
                    node.isCheckable -> "checkbox_or_switch"
                    node.isScrollable -> "scroll_container"
                    node.isClickable -> "button_or_link"
                    else -> "text"
                }
                out.add(
                    ScreenElement(
                        text = text.take(80), description = desc.take(80),
                        clickable = node.isClickable, editable = node.isEditable,
                        scrollable = node.isScrollable, selected = node.isSelected,
                        enabled = node.isEnabled, role = role,
                        left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom
                    )
                )
            }
            for (i in 0 until node.childCount) {
                if (out.size >= 60) return
                node.getChild(i)?.let { collectScreenElements(it, out, depth + 1) }
            }
        }
    }
}
