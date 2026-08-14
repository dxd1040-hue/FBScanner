package com.example.fbscanner

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

class ScannerAccessibilityService : AccessibilityService() {

    private val TAG = "FBScannerService"
    private val scope = CoroutineScope(Dispatchers.Default)
    private var lastProcessedTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("facebook")) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < 500) return
        lastProcessedTime = currentTime

        val root = rootInActiveWindow ?: return
        processRoot(root)
    }

    override fun onInterrupt() {}

    private fun processRoot(root: AccessibilityNodeInfo) {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectTextNodes(root, nodes)

        val prefs = getSharedPreferences("fbscanner", MODE_PRIVATE)
        val keywords = prefs.getString("keywords", "")
            ?.split('\n')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() } ?: emptyList()

        if (keywords.isEmpty()) return

        val processed = prefs.getStringSet("processedIds", emptySet())?.toMutableSet() ?: mutableSetOf()
        val botToken = prefs.getString("botToken", "") ?: ""
        val chatStr = prefs.getString("chatIds", "") ?: ""

        if (botToken.isBlank() || chatStr.isBlank()) {
            Log.d(TAG, "missing token/chatIds")
            return
        }

        val chatList = chatStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        for (node in nodes) {
            val raw = node.text?.toString() ?: continue
            if (raw.length < 30) continue

            val publisher = detectPublisherInfo(node)
            var content = raw
            if (publisher.name.isNotBlank()) {
                content = content.replace(publisher.name, " ", ignoreCase = true)
            }

            val contentLower = content.lowercase()
            val matched = keywords.any { contentLower.contains(it) }
            if (!matched) continue

            val id = generateId(content.take(200))
            if (processed.contains(id)) {
                Log.d(TAG, "already processed $id")
                continue
            }

            val msg = buildString {
                if (publisher.name.isNotBlank()) append("Publisher: ${publisher.name} (${publisher.type})\n\n")
                append("Content:\n")
                append(content.take(4000))
            }

            scope.launch {
                val results = mutableListOf<Pair<String, String>>()
                for (chatId in chatList) {
                    try {
                        val resp = TelegramSender.send(botToken, chatId, msg)
                        results.add(Pair(chatId, resp?.optString("description") ?: resp?.optString("result") ?: "ok"))
                    } catch (e: Exception) {
                        results.add(Pair(chatId, "error:${e.message}"))
                    }
                }
                
                synchronized(processed) {
                    processed.add(id)
                    prefs.edit().putStringSet("processedIds", processed).apply()
                }
                Log.d(TAG, "Sent id $id results: $results")
            }
        }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        try {
            if (node.childCount == 0) {
                if (!node.text.isNullOrBlank()) {
                    out.add(node)
                }
            } else {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    collectTextNodes(child, out)
                }
            }
        } catch (e: Exception) {
            // تجاهل أخطاء الواجهة
        }
    }

    private data class Publisher(val name: String, val type: String, val href: String)

    private fun detectPublisherInfo(node: AccessibilityNodeInfo): Publisher {
        try {
            val parent = node.parent ?: return Publisher("", "unknown", "")
            for (i in 0 until parent.childCount) {
                val sib = parent.getChild(i) ?: continue
                val txt = sib.text?.toString() ?: ""
                if (txt.isNotBlank() && txt.length < 60) {
                    val type = if (txt.contains("group", ignoreCase = true)) "group" else "person_or_page"
                    return Publisher(txt.trim(), type, "")
                }
            }
        } catch (e: Exception) {
            /* ignore */
        }
        return Publisher("", "unknown", "")
    }

    private fun generateId(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
