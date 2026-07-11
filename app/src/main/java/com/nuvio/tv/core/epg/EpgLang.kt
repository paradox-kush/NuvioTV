package com.nuvio.tv.core.epg

/**
 * Best-effort language/region tag for a matched channel — the same event airs on dozens of
 * channels in different languages and the match sheet should say which is which. Signals in
 * confidence order (from the research's event study): programme-title script > provider
 * name prefix ("TR:", "BR|") > EPG id country suffix (".fr"). Returns a short display tag
 * ("AR", "FR", "UK") or null when nothing is confident enough.
 */
object EpgLang {

    private val PREFIX = Regex("^\\s*([A-Za-z]{2,3})\\s*[|:•\\-]")
    private val ID_SUFFIX = Regex("\\.([a-z]{2,3})$")

    /** Tokens that look like a region prefix but aren't one. */
    private val NOT_REGION = setOf("HD", "SD", "FHD", "UHD", "VIP", "RAW", "TNT", "SKY", "BBC", "ITV", "CBS", "NBC", "ESP", "FOX")

    fun of(epgId: String?, providerName: String?, programmeTitle: String?): String? {
        programmeTitle?.let { scriptTag(it) }?.let { return it }
        providerName?.let { name ->
            PREFIX.find(name)?.groupValues?.get(1)?.uppercase()
                ?.takeIf { it !in NOT_REGION }
                ?.let { return it }
        }
        epgId?.let { id ->
            ID_SUFFIX.find(id.lowercase())?.groupValues?.get(1)?.uppercase()
                ?.let { return it }
        }
        return null
    }

    /** Unicode-script sniff of a programme title — confident when non-Latin. */
    private fun scriptTag(title: String): String? {
        for (ch in title) {
            when (ch.code) {
                in 0x0600..0x06FF, in 0x0750..0x077F -> return "AR"
                in 0x0400..0x04FF -> return "CYR"
                in 0x0370..0x03FF -> return "EL"
                in 0x0900..0x097F -> return "HI"
                in 0x4E00..0x9FFF, in 0x3040..0x30FF -> return "CJK"
                in 0x0E00..0x0E7F -> return "TH"
            }
        }
        return null
    }
}
