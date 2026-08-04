package com.sec.domain

import java.util.Base64

// The opaque route handle for __id (R5): base64url, reversible without server state, never the
// raw __id itself. Encode/decode happen only here — never inline in a route handler.
public object Ref {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    public fun encode(id: String): String = encoder.encodeToString(id.toByteArray(Charsets.UTF_8))

    public fun decode(ref: String): String = String(decoder.decode(ref), Charsets.UTF_8)
}
