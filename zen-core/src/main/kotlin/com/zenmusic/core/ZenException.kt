package com.zenmusic.core

/** Base type for all ZenMusic engine failures. */
open class ZenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A zen:// URI could not be resolved into a playable stream. */
class ZenResolutionException(message: String, cause: Throwable? = null) : ZenException(message, cause)

/** BotGuard / PO token minting failed. */
class ZenTokenException(message: String, cause: Throwable? = null) : ZenException(message, cause)

/** Vault tamper / missing secret / integrity failure. */
class ZenVaultException(message: String, cause: Throwable? = null) : ZenException(message, cause)

/** Upstream extraction returned an unexpected shape (engine may need an update). */
class ZenExtractionException(message: String, cause: Throwable? = null) : ZenException(message, cause)
