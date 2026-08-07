package com.sec.domain

import java.security.SecureRandom
import java.util.UUID

// __metaId is UUID v7 (CLAUDE.md §2 R2) so ids sort roughly by creation time. The JDK only
// generates v4 (java.util.UUID.randomUUID()); there is no dependency in the root pom.xml
// that provides v7, and the format is simple enough that adding one would violate "prefer fewer
// libraries over convenience wrappers" (CLAUDE.md §4).
public object UuidV7 {
    private val random = SecureRandom()

    public fun generate(): String {
        val value = ByteArray(16)
        random.nextBytes(value)

        val millis = System.currentTimeMillis()
        value[0] = (millis shr 40).toByte()
        value[1] = (millis shr 32).toByte()
        value[2] = (millis shr 24).toByte()
        value[3] = (millis shr 16).toByte()
        value[4] = (millis shr 8).toByte()
        value[5] = millis.toByte()

        value[6] = ((value[6].toInt() and 0x0F) or 0x70).toByte() // version 7
        value[8] = ((value[8].toInt() and 0x3F) or 0x80).toByte() // variant 10xxxxxx

        var mostSigBits = 0L
        var leastSigBits = 0L
        for (i in 0..7) mostSigBits = (mostSigBits shl 8) or (value[i].toLong() and 0xFF)
        for (i in 8..15) leastSigBits = (leastSigBits shl 8) or (value[i].toLong() and 0xFF)

        return UUID(mostSigBits, leastSigBits).toString()
    }
}
