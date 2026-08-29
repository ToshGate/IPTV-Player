package com.tosh.iptvplayer.data

import android.util.Xml
import com.tosh.iptvplayer.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Streams an XMLTV document and extracts <programme> entries, keyed by channel id
 * (this is the same id used as tvg-id in the M3U playlist).
 *
 * Typical XMLTV entry:
 * <programme start="20260826195800 +0100" stop="20260826210100 +0100" channel="rtp1.pt">
 *   <title lang="pt">Telejornal</title>
 *   <desc lang="pt">...</desc>
 * </programme>
 */
object XmltvParser {

    // XMLTV timestamps: yyyyMMddHHmmss Z (offset), e.g. 20260826195800 +0100
    private val formatWithOffset = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val formatNoOffset = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun parse(input: InputStream): List<EpgProgramme> {
        val programmes = mutableListOf<EpgProgramme>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var eventType = parser.eventType
        var channelId: String? = null
        var startMillis = 0L
        var stopMillis = 0L
        var title: String? = null
        var desc: String? = null
        var inTitle = false
        var inDesc = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channelId = parser.getAttributeValue(null, "channel")
                        startMillis = parseTime(parser.getAttributeValue(null, "start"))
                        stopMillis = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title = (title.orEmpty() + parser.text)
                    if (inDesc) desc = (desc.orEmpty() + parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        val cid = channelId
                        val t = title
                        if (cid != null && t != null && startMillis > 0 && stopMillis > 0) {
                            programmes += EpgProgramme(
                                channelTvgId = cid,
                                title = t.trim(),
                                description = desc?.trim(),
                                startMillis = startMillis,
                                stopMillis = stopMillis
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return programmes
    }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            if (raw.contains(" ")) {
                formatWithOffset.parse(raw)?.time ?: 0L
            } else {
                formatNoOffset.parse(raw)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
