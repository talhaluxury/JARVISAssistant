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
            }
        } catch (e: Exception) {
            false
        }

        if (handled) {
            queue.poll()
            attemptsOnCurrentStep = 0
            val delay = if (step is AutomationStep.Wait) step.milliseconds else 250L
            handler.postDelayed({ tryRunNextStep() }, delay)
        } else {
            attemptsOnCurrentStep++
            if (attemptsOnCurrentStep > MAX_ATTEMPTS_PER_STEP) {
                // Give up on this one step so the whole automation doesn't hang forever;
                // move on so the rest of the sequence still gets a chance to run.
                queue.poll()
                attemptsOnCurrentStep = 0
            }
        }
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

        /** Queues a sequence of steps to run against whatever app is currently in the foreground. */
        fun enqueue(steps: List<AutomationStep>) {
            queue.addAll(steps)
            val service = instance ?: return
            service.handler.post(service.pollRunnable)
        }
    }
}
