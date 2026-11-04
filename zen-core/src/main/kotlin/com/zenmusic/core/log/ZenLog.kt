package com.zenmusic.core.log

import com.zenmusic.core.vault.ZenVault

/**
 * Engine logger. Disabled by default and **always scrubbed**: any host,
 * token, or signature that could reveal the upstream media platform is
 * redacted before the message leaves the process.
 */
object ZenLog {

    @Volatile
    var enabled: Boolean = false

    fun d(message: () -> String) {
        if (!enabled) return
        println("ZN/D ${scrub(message())}")
    }

    fun d(message: String) {
        if (!enabled) return
        println("ZN/D ${scrub(message)}")
    }

    fun w(message: String, t: Throwable? = null) {
        if (!enabled) return
        println("ZN/W ${scrub(message)}${t?.let { " [${it.javaClass.simpleName}]" } ?: ""}")
    }

    /**
     * Redacts everything that could reveal the upstream platform from [text]:
     * sealed host names, PO tokens, signatures, IPs, and generic query tokens.
     */
    fun scrub(text: String): String {
        var out = text
        for (id in HOST_IDS) {
            ZenVault.openOrNull(id)?.let { host ->
                if (host.isNotEmpty()) out = out.replace(host, MASK_HOST)
            }
        }
        out = out.replace(TOKEN_REGEX, "$1**")
        out = out.replace(IP_REGEX, "$1*.*.*.*")
        return out
    }

    private const val MASK_HOST = "media.node"

    private val HOST_IDS = listOf("gv.host", "it.host", "jnn.host", "lrc.host")

    private val TOKEN_REGEX = Regex("((?:pot|signature|sparams|ei|expire)=)[^&\\s\"]+")
    private val IP_REGEX = Regex("(ip=)[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
}
