package com.tosh.iptvplayer.model

/**
 * How much of the stream ExoPlayer preloads/keeps buffered ahead during playback. Lower values
 * mean less delay behind live but more risk of stalling on a shaky connection; higher values
 * trade some extra delay for a smoother, more stable playback.
 */
enum class BufferMode(
    val label: String,
    val description: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    // How far behind the actual live edge playback targets. This — not the buffer sizes above —
    // is what actually determines the visible delay behind live for a live stream; ExoPlayer
    // decides its live playback position independently of the local resilience buffer.
    val liveTargetOffsetMs: Long
) {
    LOW(
        "Baixo",
        "Início em ~1,5 seg, guarda 5–15 seg à frente. Mais próximo do direto, mais sujeito a interrupções em ligações instáveis.",
        minBufferMs = 5_000,
        maxBufferMs = 15_000,
        bufferForPlaybackMs = 1_500,
        bufferForPlaybackAfterRebufferMs = 3_000,
        liveTargetOffsetMs = 3_000
    ),
    MEDIUM(
        "Médio (recomendado)",
        "Início em ~2,5 seg, guarda 50 seg à frente. Equilíbrio entre atraso e estabilidade — valores por omissão do reprodutor.",
        minBufferMs = 50_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 2_500,
        bufferForPlaybackAfterRebufferMs = 5_000,
        liveTargetOffsetMs = 8_000
    ),
    HIGH(
        "Alto",
        "Início em ~5 seg, guarda até 90 seg à frente. Reduz interrupções em ligações instáveis, à custa de mais atraso em relação ao direto.",
        minBufferMs = 30_000,
        maxBufferMs = 90_000,
        bufferForPlaybackMs = 5_000,
        bufferForPlaybackAfterRebufferMs = 8_000,
        liveTargetOffsetMs = 20_000
    ),
    // Field values here are placeholders — the real numbers come from the user-entered seconds
    // (see SourceRepository.getEffectiveBufferSettings()), not from this enum constant.
    CUSTOM(
        "Personalizado",
        "Define o teu próprio tempo de buffer/atraso em segundos.",
        minBufferMs = 0,
        maxBufferMs = 0,
        bufferForPlaybackMs = 0,
        bufferForPlaybackAfterRebufferMs = 0,
        liveTargetOffsetMs = 0
    );

    companion object {
        fun fromName(name: String?): BufferMode =
            values().find { it.name == name } ?: MEDIUM
    }
}

/** The actual numeric buffer configuration to hand to the player — either a preset BufferMode's
 * fixed values, or derived from the user's custom seconds input. See
 * SourceRepository.getEffectiveBufferSettings(). */
data class BufferSettings(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val liveTargetOffsetMs: Long
)
