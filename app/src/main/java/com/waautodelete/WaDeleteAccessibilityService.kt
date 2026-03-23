package com.waautodelete

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * WaDeleteAccessibilityService — v4
 *
 * Root cause fix: WhatsApp obfuscates or changes the `from_name` resource ID
 * across versions. Instead of relying on that ID, v4 uses structural/heuristic
 * detection:
 *
 *   Strategy A (ID-based):    still tries known from_name ids, in case they work
 *   Strategy B (structural):  for every long-pressable message bubble, walks its
 *                             sibling/cousin nodes looking for a small TextView
 *                             whose text matches a blocked contact — this is what
 *                             WhatsApp renders as the coloured sender name above
 *                             a group message bubble
 *   Strategy C (text-scan):   walks EVERY visible TextView; if any contains
 *                             exactly a blocked name, finds the nearest
 *                             long-pressable neighbour
 *   Strategy D (private):     toolbar title match → first long-pressable bubble
 */
class WaDeleteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WA_SVC"

        const val ACTION_FORCE_SCAN  = "com.waautodelete.FORCE_SCAN"
        const val ACTION_SCAN_RESULT = "com.waautodelete.SCAN_RESULT"
        const val EXTRA_RESULT_TEXT  = "result_text"

        // Known sender-label id suffixes (tried but not relied upon)
        private val SENDER_LABEL_IDS = listOf(
            "name_in_group",                                          // confirmed on this device
            "from_name", "author", "sender_name", "message_author",
            "name", "contact_name_text", "msg_author"
        )

        // Toolbar title ids
        private val TOOLBAR_IDS = listOf(
            "conversation_contact_name", "toolbar_title",
            "contact_name", "conversation_title"
        )

        // Delete button ids / texts
        private val DELETE_IDS   = listOf("delete", "action_delete", "mnu_delete", "menuitem_delete")
        private val DELETE_TEXTS = listOf("Delete", "delete", "حذف", "Eliminar", "Supprimer")

        // Delete-for-me button ids / texts
        private val DFM_IDS   = listOf("delete_for_me_btn", "md_buttonDefaultPositive", "button1", "button_delete_for_me")
        private val DFM_TEXTS = listOf("Delete for me", "delete for me", "حذف بالنسبة لي", "For me", "Solo para mí")

        private const val STEP_DELAY      = 700L
        private const val LONG_STEP_DELAY = 1400L
        private const val SCAN_DEBOUNCE   = 400L
        private const val MAX_RETRIES     = 3
    }

    private enum class State { IDLE, WAITING_CONTEXT_MENU, WAITING_CONFIRM_DIALOG, BACK_OFF }

    @Volatile private var state      = State.IDLE
    @Volatile private var retries    = 0
    @Volatile private var dumpedTree = false

    private val handler = Handler(Looper.getMainLooper())
    private var scanJob: Runnable? = null

    private val forceScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_SCAN) {
                Log.i(TAG, "Force scan triggered")
                state = State.IDLE
                dumpedTree = false
                scanScreen(force = true)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Connected")
        registerReceiver(forceScanReceiver, IntentFilter(ACTION_FORCE_SCAN), Context.RECEIVER_NOT_EXPORTED)
        toast("WA Auto Delete active ✓")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(forceScanReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    // ── Events ────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!ContactsRepository.isServiceEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                state = State.IDLE; dumpedTree = false
                scheduleScan(SCAN_DEBOUNCE)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> when (state) {
                State.IDLE, State.BACK_OFF       -> scheduleScan(SCAN_DEBOUNCE)
                State.WAITING_CONTEXT_MENU       -> scheduleStep(STEP_DELAY) { tapDelete() }
                State.WAITING_CONFIRM_DIALOG     -> scheduleStep(STEP_DELAY) { tapDeleteForMe() }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (state == State.IDLE || state == State.BACK_OFF) scheduleScan(SCAN_DEBOUNCE)
            else -> {}
        }
    }

    override fun onInterrupt() { state = State.IDLE }

    // ── Scheduling ────────────────────────────────────────────────────────────

    private fun scheduleScan(delay: Long) {
        scanJob?.let { handler.removeCallbacks(it) }
        val r = Runnable { scanScreen() }
        scanJob = r
        handler.postDelayed(r, delay)
    }

    private fun scheduleStep(delay: Long, fn: () -> Unit) = handler.postDelayed(fn, delay)

    // ── Scan ──────────────────────────────────────────────────────────────────

    private fun scanScreen(force: Boolean = false) {
        if (!force && state != State.IDLE && state != State.BACK_OFF) return
        val root = rootInActiveWindow ?: run {
            broadcastResult("⚠ rootInActiveWindow is null.\nOpen WhatsApp first, then tap Force Scan.")
            return
        }

        if (!dumpedTree || force) {
            dumpedTree = true
            AccessibilityDiagnostics.dump(root)           // full tree → WA_TREE logcat tag
        }

        val blocked = ContactsRepository.getContacts(this)
        if (blocked.isEmpty()) { root.recycle(); broadcastResult("ℹ No blocked contacts configured."); return }

        val log = StringBuilder()
        val target = findTarget(root, blocked, log)
        root.recycle()

        broadcastResult(log.toString())
        if (target == null) { state = State.IDLE; return }
        longPress(target)
    }

    // ── Multi-strategy finder ─────────────────────────────────────────────────

    private fun findTarget(root: AccessibilityNodeInfo, blocked: List<String>, log: StringBuilder): AccessibilityNodeInfo? {

        // ── Strategy A: known sender-label resource-id suffixes ──────────────
        log.append("Strategy A — known sender-label IDs\n")
        for (suffix in SENDER_LABEL_IDS) {
            val nodes = findByIdSuffix(root, suffix)
            if (nodes.isEmpty()) continue
            log.append("  id suffix \"$suffix\" → ${nodes.size} nodes\n")
            for (node in nodes) {
                // The node itself may be a container (LinearLayout); read text from it
                // or from its first TextView child
                val txt = nodeText(node)
                log.append("    text: \"$txt\"\n")
                if (txt.isNotBlank() && blocked.any {
                        txt.trim().equals(it.trim(), ignoreCase = true) ||
                        txt.trim().contains(it.trim(), ignoreCase = true)
                    }) {
                    log.append("  ✓ Match on id=$suffix text=\"$txt\"\n")
                    val bubble = bubbleNear(node)
                    nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    return if (bubble != null) {
                        log.append("  ✓ Bubble found\n"); bubble
                    } else {
                        log.append("  ✗ No bubble found near label — trying position search\n")
                        val byPos = bubbleByPosition(root, node)
                        if (byPos != null) log.append("  ✓ Bubble found by position\n")
                        else log.append("  ✗ Position search also failed\n")
                        byPos
                    }
                } else {
                    node.recycle()
                }
            }
            nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
        }

        // ── Strategy B: structural — collect all TextViews, find blocked names ─
        // In WhatsApp groups the sender label is a small coloured TextView that
        // sits just above (sibling-before) the message bubble in the same row.
        // We don't need its resource-id at all.
        log.append("\nStrategy B — all visible TextViews\n")
        val allTextViews = mutableListOf<AccessibilityNodeInfo>()
        collectTextViews(root, allTextViews)
        log.append("  total TextViews: ${allTextViews.size}\n")

        // Log every unique text so the user can see what's on screen
        val seen = mutableSetOf<String>()
        allTextViews.forEach { n ->
            val t = n.text?.toString()?.trim() ?: return@forEach
            if (t.isNotEmpty() && seen.add(t))
                log.append("  \"$t\"  id=${n.viewIdResourceName ?: "(none)"}\n")
        }

        for (labelNode in allTextViews) {
            val txt = nodeText(labelNode).trim()
            if (txt.isEmpty()) { labelNode.recycle(); continue }
            if (blocked.any { txt.equals(it.trim(), ignoreCase = true) ||
                              txt.contains(it.trim(), ignoreCase = true) }) {
                log.append("\n  ✓ Blocked name found in TextView: \"$txt\"\n")
                val bubble = bubbleNear(labelNode)
                allTextViews.forEach { try { it.recycle() } catch (_: Exception) {} }
                return if (bubble != null) {
                    log.append("  ✓ Bubble found\n"); bubble
                } else {
                    log.append("  ✗ No long-pressable bubble found near this label\n")
                    // Last resort: find ANY long-pressable near this node by position
                    val byPos = bubbleByPosition(root, labelNode)
                    if (byPos != null) { log.append("  ✓ Bubble found by position\n"); byPos }
                    else { log.append("  ✗ Position search also failed\n"); null }
                }
            }
            labelNode.recycle()
        }
        allTextViews.forEach { try { it.recycle() } catch (_: Exception) {} }

        // ── Strategy C: toolbar title = blocked contact (private chat) ────────
        val title = toolbarTitle(root)
        log.append("\nStrategy C — toolbar title: \"$title\"\n")
        if (title != null && blocked.any { title.contains(it, ignoreCase = true) }) {
            log.append("  ✓ Private chat match\n")
            val bubble = firstIncomingBubble(root)
            if (bubble != null) { log.append("  ✓ Bubble found\n"); return bubble }
            log.append("  ✗ No incoming bubble\n")
        }

        log.append("\n✗ No blocked messages found.\n")
        log.append("Searching for: $blocked\n")
        log.append("\nAll TextViews listed above under Strategy B.\n")
        log.append("If you see the contact's name in that list, paste this output\n")
        log.append("and I'll fix the matching logic.\n")
        return null
    }

    // ── Structural bubble finding ─────────────────────────────────────────────

    /**
     * Given a sender-label node, tries to find the long-pressable message bubble
     * by walking up the tree (the label and bubble share a common ancestor row).
     */
    private fun bubbleNear(labelNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Walk up to 8 ancestors; at each level search the subtree for a long-pressable
        var ancestor: AccessibilityNodeInfo? = try { labelNode.parent } catch (_: Exception) { null }
        var depth = 0
        while (ancestor != null && depth < 8) {
            val bubble = firstLongPressable(ancestor)
            if (bubble != null) { ancestor.recycle(); return bubble }
            val next = try { ancestor.parent } catch (_: Exception) { null }
            ancestor.recycle()
            ancestor = next
            depth++
        }
        ancestor?.recycle()
        return null
    }

    /**
     * Fallback: find the long-pressable node whose bounds are closest (vertically)
     * to the label node's bounds. Used when tree walk fails.
     */
    private fun bubbleByPosition(root: AccessibilityNodeInfo, labelNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labelRect = android.graphics.Rect()
        labelNode.getBoundsInScreen(labelRect)

        val pressables = mutableListOf<AccessibilityNodeInfo>()
        collectLongPressable(root, pressables)

        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE

        for (node in pressables) {
            val cls = node.className?.toString() ?: ""
            if (cls.contains("Button") || cls.contains("EditText")) { node.recycle(); continue }
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            // Must be below the label (rect.top >= labelRect.top) and on same horizontal band
            if (rect.top < labelRect.top) { node.recycle(); continue }
            val dist = rect.top - labelRect.bottom
            if (dist in 0..300 && dist < bestDist) {
                best?.recycle()
                best = node
                bestDist = dist
            } else {
                node.recycle()
            }
        }
        return best
    }

    private fun firstLongPressable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isLongClickable && node.isVisibleToUser) {
            val cls = node.className?.toString() ?: ""
            if (!cls.contains("Button") && !cls.contains("EditText"))
                return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = firstLongPressable(child)
            child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun collectLongPressable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isLongClickable && node.isVisibleToUser) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLongPressable(child, out)
            child.recycle()
        }
    }

    /**
     * Returns the text of a node, or if the node itself has no text (e.g. it's
     * a LinearLayout container like name_in_group), returns the concatenated text
     * of its direct TextView children.
     */
    private fun nodeText(node: AccessibilityNodeInfo): String {
        val own = node.text?.toString()?.trim()
        if (!own.isNullOrBlank()) return own
        val sb = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.trim()
            child.recycle()
            if (!t.isNullOrBlank()) sb.append(t).append(" ")
        }
        return sb.toString().trim()
    }

    /** Collects all visible TextView nodes (any class with "Text" in the name). */
    private fun collectTextViews(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString() ?: ""
        val hasText = !node.text.isNullOrEmpty()
        // Accept TextView and any custom class that holds text and is visible
        if (hasText && node.isVisibleToUser && (cls.contains("Text") || cls.contains("Label"))) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextViews(child, out)
            child.recycle()
        }
    }

    private fun firstIncomingBubble(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pressables = mutableListOf<AccessibilityNodeInfo>()
        collectLongPressable(root, pressables)
        for (node in pressables) {
            val cls = node.className?.toString() ?: ""
            if (!cls.contains("Button") && !cls.contains("EditText")) {
                pressables.forEach { if (it != node) it.recycle() }
                return node
            }
            node.recycle()
        }
        return null
    }

    // ── Long-press ────────────────────────────────────────────────────────────

    private fun longPress(node: AccessibilityNodeInfo) {
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        node.recycle()
        Log.i(TAG, "long-click performed=$ok")
        if (!ok) { retry("long-click=false"); return }
        state = State.WAITING_CONTEXT_MENU
        retries = 0
        scheduleStep(STEP_DELAY * 2) { if (state == State.WAITING_CONTEXT_MENU) tapDelete() }
    }

    // ── Tap Delete ────────────────────────────────────────────────────────────

    private fun tapDelete() {
        val root = rootInActiveWindow ?: run { retry("root null @tapDelete"); return }
        logClickables(root, "Context menu")
        val btn = findBtn(root, DELETE_IDS, DELETE_TEXTS)
        root.recycle()
        if (btn == null) { retry("delete btn not found"); return }
        Log.i(TAG, "Tapping Delete: \"${btn.text}\" id=${btn.viewIdResourceName}")
        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK); btn.recycle()
        state = State.WAITING_CONFIRM_DIALOG; retries = 0
        scheduleStep(STEP_DELAY * 2) { if (state == State.WAITING_CONFIRM_DIALOG) tapDeleteForMe() }
    }

    // ── Tap Delete for me ─────────────────────────────────────────────────────

    private fun tapDeleteForMe() {
        val root = rootInActiveWindow ?: run { retry("root null @tapDFM"); return }
        logClickables(root, "Confirm dialog")
        val btn = findBtn(root, DFM_IDS, DFM_TEXTS)
        root.recycle()
        if (btn == null) { retry("dfm btn not found"); return }
        Log.i(TAG, "Tapping DFM: \"${btn.text}\" id=${btn.viewIdResourceName}")
        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK); btn.recycle()
        state = State.BACK_OFF; retries = 0
        scheduleScan(LONG_STEP_DELAY)
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    private fun retry(reason: String) {
        Log.w(TAG, "retry $retries/$MAX_RETRIES — $reason")
        if (++retries >= MAX_RETRIES) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            state = State.IDLE; retries = 0
        }
    }

    // ── Button finder ─────────────────────────────────────────────────────────

    private fun findBtn(root: AccessibilityNodeInfo, ids: List<String>, texts: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            val nodes = findByIdSuffix(root, id)
            val m = nodes.firstOrNull { it.isClickable && it.isVisibleToUser }
            nodes.forEach { if (it != m) it.recycle() }
            if (m != null) return m
        }
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val m = nodes.firstOrNull { it.isClickable && it.isVisibleToUser }
                ?: nodes.firstOrNull { it.isVisibleToUser }
            nodes.forEach { if (it != m) it.recycle() }
            if (m != null) {
                if (!m.isClickable) { val p = m.parent; m.recycle(); return p }
                return m
            }
        }
        return null
    }

    // ── Tree helpers ──────────────────────────────────────────────────────────

    private fun findByIdSuffix(root: AccessibilityNodeInfo, suffix: String): MutableList<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b"))
            out.addAll(root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix"))
        if (out.isNotEmpty()) return out
        collectByIdSuffix(root, "/$suffix", out)
        return out
    }

    private fun collectByIdSuffix(node: AccessibilityNodeInfo, suffix: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node.viewIdResourceName?.endsWith(suffix) == true) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectByIdSuffix(child, suffix, out)
            child.recycle()
        }
    }

    private fun toolbarTitle(root: AccessibilityNodeInfo): String? {
        for (id in TOOLBAR_IDS) {
            val nodes = findByIdSuffix(root, id)
            val text = nodes.firstOrNull()?.text?.toString()
            nodes.forEach { it.recycle() }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun logClickables(root: AccessibilityNodeInfo, label: String) {
        val list = mutableListOf<AccessibilityNodeInfo>()
        collectLongPressable(root, list) // reuse — includes clickable too
        Log.d(TAG, "$label nodes (${list.size}):")
        list.forEach { Log.d(TAG, "  id=${it.viewIdResourceName} text=\"${it.text}\" desc=\"${it.contentDescription}\"") }
        list.forEach { it.recycle() }
    }

    // ── Broadcast / toast ─────────────────────────────────────────────────────

    private fun broadcastResult(text: String) {
        sendBroadcast(Intent(ACTION_SCAN_RESULT).apply {
            putExtra(EXTRA_RESULT_TEXT, text)
            setPackage(packageName)
        })
    }

    private fun toast(msg: String) =
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
}
