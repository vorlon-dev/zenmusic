# zenmusic — Protection Architecture

This document describes what the engine does to keep its origin — and the
upstream media platform it talks to — invisible, and where the hard limits
are. No hand-waving: each layer is listed with what it stops and what it
does not.

## 1. Layered disguise

```
┌──────────────────────────────────────────────────────────────────┐
│ APP LAYER   sees only:                                           │
│   zen://<id>.google.video          (pseudo-host decoy uri)       │
│   ZenStream{opaque handle, format metadata}                      │
│   artwork bytes                                                  │
├──────────────────────────────────────────────────────────────────┤
│ RESOLVER    zen uri → validate → extract → broker                │
│   · broker: real URL XOR-obfuscated at rest in memory            │
│   · handles: random opaque tokens, TTL-bounded                   │
│   · tamper → fake-stream shower (decoys, not real playback)      │
├──────────────────────────────────────────────────────────────────┤
│ EXTRACTOR   InnerTube over HTTPS                                 │
│   · hosts, paths, keys, client ids, header names: vault-sealed   │
│   · visitor data bootstrapped internally, never exposed          │
├──────────────────────────────────────────────────────────────────┤
│ POTOKEN     BotGuard challenge → sandboxed Rhino VM → GenerateIT │
│   · tokens never logged (scrubbed even when logging is on)       │
│   · coordinator: cache + single-flight + backoff                 │
├──────────────────────────────────────────────────────────────────┤
│ TRANSPORT   Media3 DataSource → engine-owned byte channel        │
│   · getUri() keeps reporting the zen:// decoy                    │
│   · requests: neutral UA, no Referer/Origin/cookies              │
└──────────────────────────────────────────────────────────────────┘
```

## 2. The AES-256-GCM vault

Everything that could hint at the upstream (hosts, `/youtubei/v1/` path,
`X-YouTube-*` header names, client names/versions, API keys, the decoy
suffix itself) is stored as `base64(iv ‖ ciphertext ‖ tag)`.

- **Cipher:** AES-256-GCM, 12-byte IV per secret, 128-bit tag → tampered
  ciphertexts throw `ZenVaultException` instead of decrypting garbage.
- **Key splitting:** the AES key never exists as a literal. Three 32-byte
  fragments live in three different classes; the key is
  `SHA-256(A ⊕ B ⊕ C ‖ "zenmusic.vault.v1")`, derived once at runtime.
- **What it stops:** `strings` dumps, AI-assisted static analysis, secret
  scanners, casual APK inspection. Verified on the sample release APK:
  zero platform markers in the dex.

## 3. Fake-stream shower (`ZenDecoys` + `TamperDetector`)

- `shower(n)` mints plausible-but-synthetic streams: valid-looking zen
  uris, random handles, realistic format numbers, fake googlevideo-style
  URLs — flooding string dumps and debuggers with noise.
- Decoy handles are **never registered** in the broker, so decoys cannot
  play; the transport fails closed.
- Under tamper signals (attached debugger, emulator fingerprints — Android
  umbrella ships `AndroidTamperDetector`, hosts can plug their own), the
  resolver returns shower decoys *instead of* real playback metadata.

## 4. R8 / ProGuard hardening

- `consumer-rules.pro` keep **only the facade**; everything else is renamed,
  shrunk, and repackaged into `zen.a.b.*` (`-repackageclasses`,
  `-allowaccessmodification`).
- `ZenLog.d/w` call sites are removed from release bytecode
  (`-assumenosideeffects`) — no scrubbed-but-real strings survive.
- Optional junk-identifier dictionary makes decompiled output maximally
  noisy (`proguard-dict.txt`; wire it in your app module, see README).
- Inline JS (BotGuard glue) and Rhino shims are kept by name — they are
  engine mechanics, not origin markers.

## 5. What this does NOT do (be honest with yourself)

1. **Runtime traffic is observable.** Anyone watching the wire (proxy,
   VPN logs, packets) sees the real hosts. Obfuscation protects the
   binary and its API surface, not the network path.
2. **Dynamic analysis wins eventually.** Frida/LLDB on an unlocked build
   can dump decrypted strings at use sites. The vault raises the cost and
   the noise floor; it is not a cryptographic guarantee against a root
   analyst.
3. **Keys are derived, not secret-from-the-binary.** Split-key derivation
   hides the key from scanners, not from someone who reverse-engineers the
   derivation (which ships in the code by necessity). For server-anchored
   secrecy, proxy extraction through your own backend and keep keys there.
4. **Protocol drift.** InnerTube/BotGuard change; the sensitive logic is
   isolated in `ZenInnertube`, `BotGuardClient`, `BotGuardGlue` for fast
   patching. Keep them updated.
5. **Platform ToS.** Extraction of media streams generally violates the
   platform's terms of service even when technically possible. This
   library is educational; you own what you build on it.

## 6. Threat-model summary

| Attacker / tool | Outcome |
| --- | --- |
| `strings`/`grep` on APK | no platform markers (verified) |
| AI/LLM static analysis of dex | no endpoints/keys to harvest; decoy noise |
| Secret scanners on the repo | vault ciphertexts only; fragments split |
| Casual decompile (jadx) | `zen.a.b.*` soup, encrypted strings, decoys |
| Frida on rooted device | can dump runtime state (limitation #2) |
| Network observer | sees real hosts (limitation #1) |
| Debug build of host app | shower decoys instead of real metadata |
