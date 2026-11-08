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

    // ---- Agent-க்கான கூடுதல் வசதிகள் ----
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    // திரையில இருக்கிறதை எண்ணிட்ட பட்டியலா தரும்
    fun dumpScreen(): String {
        val out = StringBuilder()
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || list.size >= 70) return
            val t = n.text?.toString()?.take(60) ?: ""
            val d = n.contentDescription?.toString()?.take(60) ?: ""
            if (n.isVisibleToUser && (t.isNotEmpty() || d.isNotEmpty() || n.isClickable || n.isEditable)) {
                out.append("[").append(list.size).append("] ")
                out.append(if (n.isEditable) "INPUT " else if (n.isClickable) "BTN " else "TXT ")
                out.append(t)
                if (d.isNotEmpty() && d != t) out.append(" | ").append(d)
                if (n.isPassword) out.append(" (PASSWORD)")
                out.append('\n')
                list.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        nodes = list
        return out.toString()
    }

    fun tapIndex(i: Int): Boolean {
        val n = nodes.getOrNull(i) ?: return false
        return clickUp(n)
    }

    fun typeIndex(i: Int, text: String): Boolean {
        val n = nodes.getOrNull(i) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }
}
