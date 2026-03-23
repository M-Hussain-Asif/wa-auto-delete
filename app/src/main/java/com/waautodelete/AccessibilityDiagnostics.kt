package com.waautodelete

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Dumps the full accessibility node tree to Logcat.
 * Run this, open WhatsApp to a group chat with a target contact's message visible,
 * then filter Logcat by tag "WA_TREE" to see every node, its resource-id, text,
 * class name, and whether it is clickable / long-clickable.
 *
 * Usage: AccessibilityDiagnostics.dump(rootInActiveWindow)
 */
object AccessibilityDiagnostics {

    private const val TAG = "WA_TREE"

    fun dump(root: AccessibilityNodeInfo?) {
        if (root == null) {
            Log.e(TAG, "ROOT IS NULL — service may not have window access yet.")
            return
        }
        Log.i(TAG, "════════════ ACCESSIBILITY TREE DUMP START ════════════")
        dumpNode(root, 0)
        Log.i(TAG, "════════════ ACCESSIBILITY TREE DUMP END ══════════════")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val id      = node.viewIdResourceName ?: "(no-id)"
        val cls     = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text    = node.text?.toString()?.take(80)?.replace("\n", "↵") ?: ""
        val desc    = node.contentDescription?.toString()?.take(60) ?: ""
        val flags   = buildString {
            if (node.isClickable)     append("CLK ")
            if (node.isLongClickable) append("LNG ")
            if (node.isVisibleToUser) append("VIS ")
            if (node.isEnabled)       append("ENA ")
            if (node.isScrollable)    append("SCR ")
            if (node.isFocusable)     append("FOC ")
        }.trim()

        Log.d(TAG, "$indent[$cls] id=\"$id\" text=\"$text\" desc=\"$desc\" flags=[$flags]")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
            child.recycle()
        }
    }

    /**
     * Searches the tree for any node whose resource-id contains [keyword]
     * (case-insensitive). Useful for quickly checking if a known id fragment
     * exists at all on the current screen.
     */
    fun findByIdKeyword(root: AccessibilityNodeInfo?, keyword: String): List<String> {
        root ?: return emptyList()
        val results = mutableListOf<String>()
        findByIdKeywordRecursive(root, keyword.lowercase(), results)
        return results
    }

    private fun findByIdKeywordRecursive(
        node: AccessibilityNodeInfo,
        keyword: String,
        results: MutableList<String>
    ) {
        val id = node.viewIdResourceName ?: ""
        if (id.lowercase().contains(keyword)) {
            results.add("id=\"$id\" text=\"${node.text}\" cls=${node.className}")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByIdKeywordRecursive(child, keyword, results)
            child.recycle()
        }
    }
}
