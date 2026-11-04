package com.zenmusic.potoken.js

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Synchronous `XMLHttpRequest` shim backed by OkHttp, exposed into the
 * BotGuard JS VM. Only the surface the VM actually touches is implemented —
 * enough per the BgUtils research (GET/POST, headers, text bodies).
 *
 * NOTE: members are public on purpose — Rhino reflects into them. R8 keep
 * rules protect this class (see consumer-rules.pro).
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class JsXmlHttpRequest(private val http: OkHttpClient, private val userAgent: String) {

    var readyState: Int = 0
        private set
    var status: Int = 0
        private set
    var responseText: String = ""
        private set

    private var method: String = "GET"
    private var url: String = ""
    private val headers = LinkedHashMap<String, String>()
    private var body: String? = null

    fun open(m: String, u: String, async: Boolean = false) {
        this.method = m
        this.url = u
        this.readyState = 1
    }

    fun setRequestHeader(name: String, value: String) {
        headers[name] = value
    }

    fun send(body: Any? = null) {
        this.body = body?.toString()
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val rb = when {
                method.equals("GET", true) -> null
                else -> (this.body ?: "").toRequestBody(mediaType)
            }
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (rb != null) builder.method(method, rb)
            http.newCall(builder.build()).execute().use { resp ->
                this.status = resp.code
                this.responseText = resp.body?.string() ?: ""
                this.readyState = 4
            }
        } catch (t: Throwable) {
            this.status = 0
            this.readyState = 4
            this.responseText = ""
        }
    }

    fun getAllResponseHeaders(): String = ""

    fun abort() {}

    fun addEventListener(type: String, handler: Any?) { /* sync impl: not needed */ }
}
