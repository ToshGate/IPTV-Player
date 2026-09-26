package com.tosh.iptvplayer.model

/**
 * A deliberate delay/safety cushion behind live, in seconds — the same number drives every
 * mechanism the player has for this (how long it waits before starting, how much it keeps
 * buffered throughout, and — for streams that expose a live edge (HLS) — how far behind that
 * edge it targets). Kept as ONE consistent seconds value instead of separately-tuned knobs so it
 * behaves predictably and, importantly, works for plain/progressive live streams too: many IPTV
 * providers serve channels as a continuous raw stream with no HLS manifest, so the older
 * approach (relying mainly on the HLS-only live-offset target) had no visible effect for those —
 * only bufferForPlaybackMs actually delays a progressive source, since it has no "live edge" for
 * ExoPlayer to target an offset from at all.
 */
enum class BufferMode(
    val label: String,
    val description: String,
    val delaySeconds: Int
) {
    LOW(
        "Baixo",
        "Guarda 5 segundos antes de começar. Mais próximo do direto, mais sujeito a interrupções em ligações instáveis.",
        delaySeconds = 5
    ),
    MEDIUM(
        "Médio (recomendado)",
        "Guarda 10 segundos antes de começar. Bom equilíbrio entre atraso e estabilidade.",
        delaySeconds = 10
    ),
    HIGH(
        "Alto",
        "Guarda 20 segundos antes de começar. Reduz interrupções em ligações instáveis, à custa de mais atraso em relação ao direto.",
        delaySeconds = 20
    ),
    // Placeholder — the real value comes from the user-entered seconds (see
    // SourceRepository.getEffectiveBufferSettings()), not from this enum constant.
    CUSTOM(
        "Personalizado",
        "Define o teu próprio tempo de atraso em segundos.",
        delaySeconds = 0
    );

    companion object {
        fun fromName(name: String?): BufferMode =
            values().find { it.name == name } ?: MEDIUM

        /** Derives the full set of player-facing buffer values from a single delay figure, so
         * every BufferMode (including CUSTOM, from the user's own seconds input) is built the
         * same consistent way. ExoPlayer's DefaultLoadControl requires minBufferMs to be at
         * least as large as bufferForPlaybackAfterRebufferMs (and maxBufferMs at least
         * minBufferMs) — building minBuffer/maxBuffer FROM afterRebuffer, rather than from the
         * raw target independently, is what keeps that always true regardless of delaySeconds. */
        fun buildSettings(delaySeconds: Int): BufferSettings {
            val target = (delaySeconds.coerceAtLeast(1)) * 1_000
            val afterRebuffer = target + 2_000
            val minBuffer = afterRebuffer + 1_000
            val maxBuffer = minBuffer + target
            return BufferSettings(
                minBufferMs = minBuffer,
                maxBufferMs = maxBuffer,
                bufferForPlaybackMs = target,
                bufferForPlaybackAfterRebufferMs = afterRebuffer,
                liveTargetOffsetMs = target.toLong()
            )
        }
    }
}

/** The actual numeric buffer configuration to hand to the player — derived from a BufferMode's
 * delaySeconds (or the user's custom seconds input) via BufferMode.buildSettings(). See
 * SourceRepository.getEffectiveBufferSettings(). */
data class BufferSettings(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val liveTargetOffsetMs: Long
)
