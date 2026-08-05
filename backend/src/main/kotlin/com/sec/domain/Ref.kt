package com.sec.domain

import java.util.Base64

// The opaque route handle for __id (R5): base64url, reversible without server state, never the
// raw __id itself. Encode/decode happen only here — never inline in a route handler.
public object Ref {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    public fun encode(id: String): String = encoder.encodeToString(id.toByteArray(Charsets.UTF_8))

    // A :ref arrives from the address bar, so malformed input is an expected failure, not an
    // exception — the decoder throws IllegalArgumentException on anything that is not base64url
    // and an uncaught throw here would report a client error as a 500.
    public fun decodeOrNull(ref: String): String? =
        runCatching { String(decoder.decode(ref), Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}
