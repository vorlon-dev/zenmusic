package com.zenmusic.potoken.js

/**
 * JS glue executed inside [RhinoJsEngine].
 *
 * PROMISE_POLYFILL — compact, synchronous-scheduler Promise for Rhino (it has
 * no microtask queue; the BotGuard VM only needs linear then-chains).
 *
 * MINT_GLUE — the mint pipeline, mirroring the flow documented by
 * LuanRT/BgUtils (v4 research):
 *   1. `botguard.bg.async(program, false)`   → VM instance
 *   2. `vm.snapshot({identifier, webPoSignalOutput})`
 *   3. GenerateIT exchange via the Kotlin bridge (request/shape below)
 *   4. `vm.integrityTokenBasedMinter({integrityToken, webPoSignalOutput})`
 *   5. `minter.mintAsWebsafeString(identifier)` → PO token
 *
 * If upstream rotates the protocol, only this file + [com.zenmusic.potoken.BotGuardClient]
 * need patching — no other engine code touches the VM.
 */
object BotGuardGlue {

    val PROMISE_POLYFILL = """
        (function (global) {
            if (typeof global.Promise === 'function' && typeof global.Promise.resolve === 'function') return;
            function P(exec) {
                var st = 0, val, subs = [];
                function fire() {
                    while (subs.length) {
                        var s = subs.shift();
                        try {
                            if (st === 1) { if (s.onOk) s.onOk(val); else s.next.res(val); }
                            else if (st === 2) { if (s.onErr) s.onErr(val); else s.next.rej(val); }
                        } catch (e) { if (s.next) s.next.rej(e); }
                    }
                }
                this.res = function (v) { if (st !== 0) return; st = 1; val = v; fire(); };
                this.rej = function (v) { if (st !== 0) return; st = 2; val = v; fire(); };
                this.then = function (onOk, onErr) {
                    var next = new P(function () {});
                    subs.push({ onOk: onOk, onErr: onErr, next: next });
                    if (st !== 0) fire();
                    return next;
                };
                this.catch = function (onErr) { return this.then(null, onErr); };
                try { exec(this.res, this.rej); } catch (e) { this.rej(e); }
            }
            P.resolve = function (v) {
                if (v && typeof v.then === 'function') return v;
                return new P(function (res) { res(v); });
            };
            P.reject = function (v) { return new P(function (_, rej) { rej(v); }); };
            P.all = function (arr) {
                return new P(function (res) { res(Array.prototype.slice.call(arr)); });
            };
            global.Promise = P;
        })(this);
    """.trimIndent()

    val MINT_GLUE = """
        function __zenMint(programInput, identifier) {
            var program = (typeof programInput === 'string') ? JSON.parse(programInput) : programInput;
            var programRaw = (typeof programInput === 'string') ? programInput : JSON.stringify(programInput);
            var webPoSignalOutput = [];

            var bgFactory = (typeof botguard !== 'undefined' && botguard) ? botguard : (window && window.botguard);
            if (!bgFactory || !bgFactory.bg) { throw new Error('botguard VM factory missing'); }

            var start;
            if (typeof bgFactory.bg.async === 'function') { start = function () { return bgFactory.bg.async(program, false); }; }
            else if (typeof bgFactory.bg === 'function') { start = function () { return bgFactory.bg(program, false); }; }
            else { throw new Error('botguard.bg entry not found'); }

            return Promise.resolve(start()).then(function (vm) {
                if (!vm || typeof vm.snapshot !== 'function') { throw new Error('botguard vm missing snapshot()'); }
                var snap = vm.snapshot({ identifier: identifier, webPoSignalOutput: webPoSignalOutput });
                return Promise.resolve(snap).then(function (s) {
                    var genReq = JSON.stringify([__requestKey, programRaw,
                        (webPoSignalOutput && webPoSignalOutput.length) ? webPoSignalOutput : [], []]);
                    var genRespRaw = __bridge.generateIt(genReq);
                    var genResp = JSON.parse(genRespRaw);
                    if (!genResp || genResp.length === 0 || genResp[0] == null) {
                        throw new Error('generate-it: empty integrity token');
                    }
                    var integrityToken = genResp[0];
                    var ttlSeconds = (genResp.length > 1 && genResp[1]) ? genResp[1] : 86400;
                    if (typeof vm.integrityTokenBasedMinter !== 'function') {
                        throw new Error('botguard vm missing integrityTokenBasedMinter()');
                    }
                    return Promise.resolve(
                        vm.integrityTokenBasedMinter({ integrityToken: integrityToken, webPoSignalOutput: webPoSignalOutput })
                    ).then(function (minter) {
                        if (!minter || typeof minter.mintAsWebsafeString !== 'function') {
                            throw new Error('minter missing mintAsWebsafeString()');
                        }
                        var token = minter.mintAsWebsafeString(identifier);
                        if (!token) { throw new Error('empty po token'); }
                        return { token: String(token), ttlSeconds: Number(ttlSeconds) || 86400 };
                    });
                });
            });
        }
    """.trimIndent()
}
