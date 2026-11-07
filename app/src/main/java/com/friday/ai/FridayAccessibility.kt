package com.friday.ai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// மற்ற ஆப்ல tap / type / scroll பண்ண இந்த service தான் கை மாதிரி.
// Settings → Accessibility → Friday → ON பண்ணணும்.
class FridayAccessibility : AccessibilityService() {
    companion object { var instance: FridayAccessibility? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun typeText(t: String): Boolean {
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun clickUp(n: AccessibilityNodeInfo?): Boolean {
        var x = n
        while (x != null) { if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK); x = x.parent }
        return false
    }

    fun clickText(t: String): Boolean =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(t)?.firstOrNull()?.let { clickUp(it) } ?: false

    // contentDescription-ல குறிப்பிட்ட வார்த்தை இருக்கிற முதல் node-ஐ click (YouTube வீடியோவுக்கு)
    fun clickDescContains(sub: String): Boolean {
        fun dfs(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.contentDescription?.contains(sub, true) == true) return n
            for (i in 0 until n.childCount) dfs(n.getChild(i))?.let { return it }
            return null
        }
        return clickUp(dfs(rootInActiveWindow))
    }

    fun scroll(down: Boolean): Boolean {
        fun dfs(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) dfs(n.getChild(i))?.let { return it }
            return null
        }
        val s = dfs(rootInActiveWindow) ?: return false
        return s.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
}
