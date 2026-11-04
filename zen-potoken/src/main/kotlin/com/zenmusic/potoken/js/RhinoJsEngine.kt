package com.zenmusic.potoken.js

import okhttp3.OkHttpClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Sandboxed Rhino JS runtime used to execute the BotGuard interpreter script.
 *
 * Hardening:
 *  - interpreter mode (`optimizationLevel = -1`) so the VM bytecode runs;
 *  - wall-clock budget enforced through Rhino's instruction observer —
 *    runaway scripts throw instead of hanging the minting coroutine;
 *  - no host-object access beyond the explicit shims/bridge we register;
 *  - fresh scope per mint session; closed after use.
 */
class ZenJsContextFactory(private val maxRuntimeMs: Long) : ContextFactory() {

    @Volatile
    private var startedAtMs: Long = 0L

    fun arm() {
        startedAtMs = System.currentTimeMillis()
    }

    fun disarm() {
        startedAtMs = 0L
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        val started = startedAtMs
        if (started > 0 && System.currentTimeMillis() - started > maxRuntimeMs) {
            throw IllegalStateException("js runtime budget exceeded")
        }
    }
}

class RhinoJsEngine(
    http: OkHttpClient,
    userAgent: String,
    private val generateIt: (String) -> String,
    private val requestKey: String,
    maxRuntimeMs: Long = 30_000L,
) : AutoCloseable {

    companion object {
        /** Instruction observer callback granularity (instructions). */
        private const val OBSERVER_THRESHOLD = 20_000
    }

    private val factory = ZenJsContextFactory(maxRuntimeMs)
    private val bridge = JsBridge(http, userAgent, generateIt)
    private val ctx: Context = factory.enterContext().apply {
        optimizationLevel = -1 // interpreter mode: required by the BotGuard VM
        languageVersion = Context.VERSION_ES6
        instructionObserverThreshold = OBSERVER_THRESHOLD
    }
    private val scope: Scriptable = ctx.initStandardObjects()

    init {
        defineGlobal("__bridge", bridge)
        defineGlobal("__requestKey", requestKey)
        defineGlobal("document", JsShims.JsDocument())
        defineGlobal("navigator", JsShims.JsNavigator())
        defineGlobal("location", JsShims.JsLocation())
        defineGlobal("console", JsShims.JsConsole())
        // window === global scope, as most web scripts assume
        ScriptableObject.putProperty(scope, "window", scope)
        // XMLHttpRequest factory → fresh shim instance for this session
        val xhrInstance = bridge.newXhr()
        defineGlobal("__xhrInstance", xhrInstance)
        ctx.evaluateString(
            scope,
            "var XMLHttpRequest = function(){ return __xhrInstance; };",
            "zen-xhr",
            1,
            null,
        )
        // Promise polyfill (sync scheduler) for Rhino
        ctx.evaluateString(scope, BotGuardGlue.PROMISE_POLYFILL, "zen-promise", 1, null)
        ctx.evaluateString(scope, BotGuardGlue.MINT_GLUE, "zen-mint", 1, null)
    }

    /** Evaluates [js] into the global scope (used for the interpreter script). */
    fun evaluate(js: String, sourceName: String) {
        factory.arm()
        try {
            ctx.evaluateString(scope, js, sourceName, 1, null)
        } finally {
            factory.disarm()
        }
    }

    /** True when a global named [name] exists (e.g. the `botguard` VM factory). */
    fun hasGlobal(name: String): Boolean {
        val v = scope.get(name, scope)
        return v != null && v != Scriptable.NOT_FOUND
    }

    /** Calls a global JS function with [args]; returns the converted result. */
    fun callGlobal(name: String, vararg args: Any?): Any? {
        val fn = scope.get(name, scope)
        if (fn !is Function) throw IllegalStateException("global '$name' is not a function")
        val jsArgs = args.map { Context.javaToJS(it, scope) }.toTypedArray()
        factory.arm()
        try {
            return fn.call(ctx, scope, scope, jsArgs)
        } finally {
            factory.disarm()
        }
    }

    /** Converts a JS result (NativeJavaObject wrapper) back to a Kotlin value. */
    fun unwrap(v: Any?): Any? = when (v) {
        is NativeJavaObject -> v.unwrap()
        else -> v
    }

    private fun defineGlobal(name: String, value: Any) {
        ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope))
    }

    override fun close() {
        try {
            Context.exit()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Bridge passed into the JS glue. Only these two methods are reachable from
 * the VM: one bounded HTTP shim instance and the GenerateIT exchange.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class JsBridge(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val generateIt: (String) -> String,
) {
    fun newXhr(): JsXmlHttpRequest = JsXmlHttpRequest(http, userAgent)

    /** Posts the GenerateIT payload (already JSON-stringified by the glue). */
    fun generateIt(payload: String): String = generateIt(payload)
}
