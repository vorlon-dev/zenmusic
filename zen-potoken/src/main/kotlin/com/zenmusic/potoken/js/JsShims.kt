package com.zenmusic.potoken.js

/**
 * Minimal browser-environment shims for the BotGuard VM running inside Rhino
 * (document / window / navigator / location / console / setTimeout).
 * Everything is inert: the VM needs the *shape* of a browser more than real
 * behavior.
 *
 * NOTE: public members are accessed reflectively from JS — keep rules in
 * consumer-rules.pro protect them from obfuscation.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object JsShims {

    class JsDocument {
        fun createElement(tag: String): JsElement = JsElement(tag)
        fun getElementById(id: String): JsElement? = null
        fun getElementsByTagName(tag: String): Array<Any> = arrayOf()
        @Volatile var readyState: String = "complete"
        @Volatile var hidden: Boolean = false
        var visibilityState: String = "visible"
    }

    class JsElement(val tagName: String) {
        var innerHTML: String = ""
        var style: MutableMap<String, String> = LinkedHashMap()
        fun setAttribute(k: String, v: String) {}
        fun getAttribute(k: String): String? = null
        fun appendChild(child: Any?) {}
        fun remove() {}
        @Volatile var parentNode: JsElement? = null
    }

    class JsNavigator {
        val userAgent: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val language: String = "en-US"
        val languages: Array<String> = arrayOf("en-US", "en")
        val platform: String = "Win32"
        val hardwareConcurrency: Int = 8
        val deviceMemory: Int = 8
        val webdriver: Boolean = false
        fun sendBeacon(url: String, data: Any?): Boolean = true
    }

    class JsLocation {
        var href: String = "https://www.example.com/"
        var hostname: String = "www.example.com"
        var pathname: String = "/"
        var protocol: String = "https:"
        var origin: String = "https://www.example.com"
        fun reload() {}
        fun replace(u: String) {}
    }

    class JsConsole {
        fun log(vararg args: Any?) {}
        fun warn(vararg args: Any?) {}
        fun error(vararg args: Any?) {}
        fun info(vararg args: Any?) {}
        fun debug(vararg args: Any?) {}
    }
}
