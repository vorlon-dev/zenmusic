package com.zenmusic.potoken

import com.zenmusic.core.ZenTokenException
import com.zenmusic.core.log.ZenLog
import com.zenmusic.core.vault.ZenVault
import com.zenmusic.potoken.js.BotGuardGlue
import com.zenmusic.potoken.js.RhinoJsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * BotGuard attestation client.
 *
 * Protocol follows the publicly documented flow from LuanRT/BgUtils research:
 *   Create (challenge + interpreter JS) → run VM → GenerateIT → mint PO token.
 * If the upstream rotates the protocol, patch [fetchChallenge], the
 * GenerateIT closure below, and [com.zenmusic.potoken.js.BotGuardGlue] —
 * nothing else in the engine touches it.
 */
class BotGuardClient(
    private val http: OkHttpClient,
    private val gl: String = "US",
    private val hl: String = "en",
) {

    data class Challenge(
        val program: String,
        val interpreterJs: String,
        val interpreterHash: String?,
    )

    data class MintResult(
        val token: String,
        val ttlSeconds: Long,
        val interpreterHash: String?,
    )

    private val jnnHost: String = ZenVault.open("jnn.host")
    private val jnnKey: String = ZenVault.open("jnn.key")
    private val requestKey: String = ZenVault.open("jnn.reqkey")
    private val createPath: String = ZenVault.open("jnn.create.path")
    private val generatePath: String = ZenVault.open("jnn.generate.path")
    private val webVersion: String = ZenVault.open("web.clientver")
    private val uaWeb: String = ZenVault.open("ua.web")

    /** Requests an attestation challenge and the BotGuard interpreter script. */
    suspend fun fetchChallenge(): Challenge = withContext(Dispatchers.IO) {
        val url = "https://$jnnHost$createPath"
        val body = JSONObject().apply {
            put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", webVersion)
                        .put("hl", hl)
                        .put("gl", gl),
                ),
            )
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", uaWeb)
            .header("Content-Type", "application/json+protobuf")
            .header("X-Goog-Api-Key", jnnKey)
            .post(body.toString().toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ZenTokenException("challenge rejected (${resp.code})")
                val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                val hash = arr.optString(0).takeIf { it.isNotEmpty() }
                val interpreter = arr.optJSONObject(1)
                    ?.optString("privateDoNotAccessOrElseSafeScriptWrappedValue")
                    .orEmpty()
                val program = arr.optString(2)
                if (interpreter.isEmpty() || program.isEmpty()) {
                    throw ZenTokenException("challenge response missing parts")
                }
                Challenge(program = program, interpreterJs = interpreter, interpreterHash = hash)
            }
        } catch (e: ZenTokenException) {
            throw e
        } catch (t: Throwable) {
            throw ZenTokenException("challenge fetch failed", t)
        }
    }

    /**
     * Runs the VM and mints a PO token bound to [identifier].
     * Heavy — runs on the IO dispatcher inside a sandboxed Rhino runtime.
     */
    suspend fun mint(challenge: Challenge, identifier: String): MintResult =
        withContext(Dispatchers.IO) {
            val engine = RhinoJsEngine(
                http = http,
                userAgent = uaWeb,
                generateIt = { payload -> generateIt(payload) },
                requestKey = requestKey,
            )
            try {
                engine.evaluate(challenge.interpreterJs, "bg-interpreter")
                if (!engine.hasGlobal("botguard") && !engine.hasGlobal("window")) {
                    throw ZenTokenException("VM did not initialize")
                }
                val raw = engine.callGlobal("__zenMint", challenge.program, identifier)
                val result = engine.unwrap(raw)
                @Suppress("UNCHECKED_CAST")
                val map = result as? Map<Any?, Any?>
                    ?: throw ZenTokenException("unexpected mint result shape")
                val token = map["token"]?.toString()
                    ?: throw ZenTokenException("mint returned no token")
                val ttl = (map["ttlSeconds"] as? Number)?.toLong() ?: 86_400L
                ZenLog.d { "potoken minted for binding=${identifier.take(4)}** ttl=$ttl" }
                MintResult(token = token, ttlSeconds = ttl, interpreterHash = challenge.interpreterHash)
            } catch (e: ZenTokenException) {
                throw e
            } catch (t: Throwable) {
                throw ZenTokenException("VM mint failed", t)
            } finally {
                runCatching { engine.close() }
            }
        }

    /** GenerateIT exchange executed from inside the JS glue (synchronous). */
    private fun generateIt(payload: String): String {
        val url = "https://$jnnHost$generatePath"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", uaWeb)
            .header("Content-Type", "application/json+protobuf")
            .header("X-Goog-Api-Key", jnnKey)
            .post(payload.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ZenTokenException("generate-it rejected (${resp.code})")
            return resp.body?.string().orEmpty()
        }
    }
}
