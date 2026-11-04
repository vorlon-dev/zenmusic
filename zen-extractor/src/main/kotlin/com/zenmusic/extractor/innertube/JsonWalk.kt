package com.zenmusic.extractor.innertube

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shape-agnostic helpers over InnerTube's deeply nested responses.
 * Walking by renderer name keeps the parser resilient to re-nesting that
 * the upstream periodically performs.
 */
internal object JsonWalk {

    /** Collects every [JSONObject] named [key] anywhere in the tree. */
    fun findAllObjects(node: Any?, key: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        collect(node, key, out)
        return out
    }

    /** First [JSONObject] named [key] in pre-order, or null. */
    fun findObject(node: Any?, key: String): JSONObject? {
        return when (node) {
            is JSONObject -> {
                if (node.has(key)) {
                    val v = node.opt(key)
                    if (v is JSONObject) return v
                }
                for (k in node.keys()) {
                    findObject(node.opt(k), key)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findObject(node.opt(i), key)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    /** First text run value under a `runs` array. */
    fun firstRunText(node: Any?, runsKey: String = "runs"): String? {
        val arr = runsArrayOf(node, runsKey) ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(0)?.optString("text")
    }

    /** All run texts concatenated (bylines etc.). */
    fun allRunText(node: Any?, runsKey: String = "runs"): String? {
        val arr = runsArrayOf(node, runsKey) ?: return null
        return buildString {
            for (i in 0 until arr.length()) append(arr.optJSONObject(i)?.optString("text").orEmpty())
        }.takeIf { it.isNotEmpty() }
    }

    /** Locates a `runs`-style JSONArray whether [node] is the holder, a wrapper, or a tree. */
    private fun runsArrayOf(node: Any?, runsKey: String): JSONArray? = when {
        node is JSONObject && node.opt(runsKey) is JSONArray -> node.optJSONArray(runsKey)
        else -> findArray(node, runsKey)
    }

    /** First [JSONArray] named [key] in pre-order, or null. */
    fun findArray(node: Any?, key: String): JSONArray? {
        return when (node) {
            is JSONObject -> {
                (node.opt(key) as? JSONArray)?.let { return it }
                for (k in node.keys()) {
                    findArray(node.opt(k), key)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findArray(node.opt(i), key)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    /** Text of the first run in [columns][index] of a responsive list item. */
    fun flexColumnText(item: JSONObject, index: Int): String? {
        val cols = item.optJSONArray("flexColumns") ?: return null
        val col = cols.optJSONObject(index) ?: return null
        return allRunText(col)
    }

    /** videoId attached to a responsive list item, if any. */
    fun videoIdOf(item: JSONObject): String? {
        item.optJSONObject("playlistItemData")?.optString("videoId")?.let { return it }
        var found: String? = null
        collectEndpoints(item) { obj ->
            if (found == null) {
                found = obj.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotEmpty() }
            }
        }
        return found
    }

    /** browseId attached to a responsive list item, if any. */
    fun browseIdOf(item: JSONObject): String? {
        var found: String? = null
        collectEndpoints(item) { obj ->
            if (found == null) {
                found = obj.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.isNotEmpty() }
            }
        }
        return found
    }

    /** Largest thumbnail URL from a thumbnail holder (musicThumbnailRenderer etc.). */
    fun thumbnailUrl(node: Any?): String? {
        // `videoRenderer.thumbnail` holds the array directly; music renderers
        // nest it deeper under object keys — handle both shapes.
        val arr = (when {
            node is JSONObject && node.optJSONArray("thumbnails") != null ->
                node.optJSONArray("thumbnails")
            else ->
                (findObject(node, "thumbnails") as? JSONObject)?.optJSONArray("thumbnails")
        }) ?: return null
        var best: JSONObject? = null
        var bestArea = -1L
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val area = t.optLong("width", 0L) * t.optLong("height", 0L)
            if (area > bestArea) {
                bestArea = area
                best = t
            }
        }
        return best?.optString("url")?.takeIf { it.isNotEmpty() }
    }

    private fun collectEndpoints(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                for (k in node.keys()) collectEndpoints(node.opt(k), visit)
            }
            is JSONArray -> for (i in 0 until node.length()) collectEndpoints(node.opt(i), visit)
            else -> Unit
        }
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                for (k in node.keys()) {
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v)
                    collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
            else -> Unit
        }
    }
}
