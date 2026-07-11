package com.nuvio.tv.core.epg

import java.text.Normalizer

/**
 * Provider-channel-name → EPG-channel-id matching. Kotlin port of the validated research
 * matcher (research/epg-matching/epg_match.py: 94-97% on eligible UK channels, 99% US on B1G,
 * measured against three real panels), preserving its tiers:
 *
 *   tvg     provider epg_channel_id == xmltv id (casefold), gated by name plausibility —
 *           panel tvg-ids are operator-entered and often point at the wrong channel
 *   exact   normalized "core" name equal (region prefix + quality tokens stripped)
 *   tokens  same token set (word order / duplicate insensitive)
 *   squash  space-free form equal ("SKYSPORTS F1" = "Sky Sports F1")
 *   plural  token set equal after trailing-s strip ("sky sport" = "sky sports")
 *   fuzzy   similarity >= 0.87 with same first token (review tier; used for guide/matching
 *           display, never for anything destructive)
 */
object EpgNorm {

    private val QUALITY = setOf(
        "fhd", "uhd", "hd", "sd", "4k", "8k", "hevc", "h265", "h264", "raw",
        "50fps", "60fps", "vip", "backup", "alt", "low", "mobile",
    )

    // NOTE: "plus" is NOT quality — it's identity (Sky Sports Plus, Disney Plus).
    private val COUNTRY_PREF = setOf(
        "uk", "usa", "us", "ire", "ie", "ca", "au", "nz", "in", "pk", "bd", "mx",
        "de", "ger", "fr", "fra", "it", "es", "pt", "nl", "be", "pl", "tr", "ro",
        "ru", "gr", "alb", "al", "bg", "cz", "dk", "se", "no", "fi", "br", "ar",
        "za", "eg", "iq", "ir", "is", "sa", "ae", "kw", "qa", "ex-yu", "exyu",
        "nepal", "afg", "som", "eth", "ph", "vn", "th", "my", "sg", "hk", "tw",
        "kr", "jp", "cn", "lat", "latino",
    )

    private val WORD_DIGITS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    private val GLUED_PREFIX = Regex("([a-z-]{2,6}?)(sd|hd|fhd|uhd|4k)")
    private val ID_COUNTRY_SUFFIX = Regex("\\.[a-z]{2,3}$", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("[^a-z0-9+]+")
    private val SPACES = Regex("\\s+")
    private val PLUS_ONE_TAIL = Regex("\\s*\\+\\s*1$")

    /** NFKD fold, strip combining marks, casefold, `&`→" and ", non-alnum→space. */
    fun baseNorm(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .filterNot { it.code in 0x0300..0x036F }
        return folded.lowercase()
            .replace("&", " and ")
            .replace(NON_ALNUM, " ")
            .replace(SPACES, " ")
            .trim()
    }

    /** Drop up to 3 leading region/quality tokens: "uk fhd tnt sport 2" -> "tnt sport 2". */
    private fun stripPrefix(s: String): String {
        val toks = s.split(" ")
        var i = 0
        while (i < toks.size && i < 3) {
            val t = toks[i]
            val glued = GLUED_PREFIX.matchEntire(t)
            when {
                t in COUNTRY_PREF || (glued != null && glued.groupValues[1] in COUNTRY_PREF) -> i++
                i > 0 && t in QUALITY -> i++   // quality directly after a country token
                else -> break
            }
        }
        return if (i < toks.size) toks.subList(i, toks.size).joinToString(" ") else s
    }

    /** The comparable "core" of a channel name (region/quality stripped, word-digits folded). */
    fun coreNorm(s: String): String {
        val stripped = stripPrefix(baseNorm(s))
        val toks = stripped.split(" ").filter { it.isNotEmpty() && it !in QUALITY }
            .map { WORD_DIGITS[it] ?: it }
        val out = toks.joinToString(" ")
        return out.ifEmpty { stripped }
    }

    /** 'BBC.One.HD.uk' -> 'bbc 1 hd' — an XMLTV id read as a name. */
    fun idStem(cid: String): String = coreNorm(cid.replace(ID_COUNTRY_SUFFIX, "").replace(".", " "))

    /** Alias/spelling variants of a core name, all worth indexing/looking up. */
    fun variants(core: String): Set<String> {
        val out = mutableSetOf(core)
        if (core.startsWith("u and ")) out.add(core.removePrefix("u and "))   // U&Dave = Dave
        out.add(core.replace("virgin media", "virgin"))
        out.add(core.replace(PLUS_ONE_TAIL, " plus 1"))
        return out.mapNotNull { it.trim().ifEmpty { null } }.toSet()
    }

    fun squash(core: String): String = core.replace(" ", "")

    private fun depl(tok: String): String =
        if (tok.length > 3 && tok.endsWith("s")) tok.dropLast(1) else tok

    /** Order/duplicate-insensitive token key (python frozenset equivalent). */
    fun tokenKey(core: String): String =
        core.split(" ").filter { it.isNotEmpty() }.distinct().sorted().joinToString(" ")

    fun deplKey(core: String): String =
        core.split(" ").filter { it.isNotEmpty() }.map(::depl).distinct().sorted().joinToString(" ")

    /** Levenshtein similarity in [0,1]. (The python used difflib's ratio; for channel-name
     *  lengths the two agree well above the 0.87 gate — this tier stayed a small share of
     *  matches in the study.) */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

/**
 * The lookup index over one or more EPG sources' channel lists. Built transiently during a
 * mirror sync (mappings are persisted to SQLite; this object is dropped after) — do not hold
 * one long-term, it's tens of MB for a 50k-channel index.
 */
class EpgChannelIndex private constructor(
    private val byTvg: Map<String, String>,
    private val byCore: Map<String, String>,
    private val byTokens: Map<String, String>,
    private val bySquash: Map<String, String>,
    private val byDepl: Map<String, String>,
    private val coresById: Map<String, Set<String>>,
    private val fuzzyBucket: Map<String, List<String>>,
) {

    data class Hit(val epgId: String, val tier: String)

    val size: Int get() = coresById.size

    /**
     * Match one provider channel. [tvgId] is the panel's epg_channel_id (may be garbage —
     * only trusted when the EPG channel's name is loosely compatible with [name]).
     */
    fun match(name: String, tvgId: String?): Hit? {
        val core = EpgNorm.coreNorm(name)
        val tvg = tvgId?.trim()?.lowercase().orEmpty()
        if (tvg.isNotEmpty()) {
            val cid = byTvg[tvg]
            if (cid != null && tvgPlausible(core, cid)) return Hit(cid, TIER_TVG)
        }
        if (core.isEmpty()) return null
        for (v in EpgNorm.variants(core)) {
            byCore[v]?.let { return Hit(it, TIER_EXACT) }
        }
        byTokens[EpgNorm.tokenKey(core)]?.let { return Hit(it, TIER_TOKENS) }
        bySquash[EpgNorm.squash(core)]?.let { return Hit(it, TIER_SQUASH) }
        byDepl[EpgNorm.deplKey(core)]?.let { return Hit(it, TIER_PLURAL) }

        val first = core.substringBefore(' ')
        var best: String? = null
        var bestScore = 0.0
        for (cand in fuzzyBucket[first].orEmpty()) {
            val r = EpgNorm.similarity(core, cand)
            if (r > bestScore) { best = cand; bestScore = r }
        }
        if (best != null && bestScore >= FUZZY_GATE) {
            byCore[best]?.let { return Hit(it, TIER_FUZZY) }
        }
        return null
    }

    /** Accept a tvg-id hit only when the names share a token or a 4-char squash prefix. */
    private fun tvgPlausible(core: String, cid: String): Boolean {
        if (core.isEmpty()) return false
        val toks = core.split(" ").toSet()
        val squash4 = EpgNorm.squash(core).take(4)
        for (c in coresById[cid].orEmpty()) {
            if (toks.any { it in c.split(" ") }) return true
            if (squash4.isNotEmpty() && squash4 == EpgNorm.squash(c).take(4)) return true
        }
        return false
    }

    companion object {
        const val TIER_TVG = "tvg"
        const val TIER_EXACT = "exact"
        const val TIER_TOKENS = "tokens"
        const val TIER_SQUASH = "squash"
        const val TIER_PLURAL = "plural"
        const val TIER_FUZZY = "fuzzy"
        private const val FUZZY_GATE = 0.87

        /**
         * [rows] = (epgId, displayNames) per EPG channel; first source indexed wins a key
         * (matches the research merge order semantics — pass sources in priority order).
         * epgIds are stored/returned lowercase.
         */
        fun build(rows: Iterable<Pair<String, List<String>>>): EpgChannelIndex {
            val byTvg = HashMap<String, String>()
            val byCore = HashMap<String, String>()
            val byTokens = HashMap<String, String>()
            val bySquash = HashMap<String, String>()
            val byDepl = HashMap<String, String>()
            val coresById = HashMap<String, MutableSet<String>>()
            for ((rawId, names) in rows) {
                val cid = rawId.trim().lowercase()
                if (cid.isEmpty()) continue
                byTvg.putIfAbsent(cid, cid)
                val cands = buildList {
                    add(EpgNorm.idStem(cid))
                    names.forEach { if (it.isNotBlank()) add(EpgNorm.coreNorm(it)) }
                }
                for (cand0 in cands) {
                    for (cand in EpgNorm.variants(cand0)) {
                        byCore.putIfAbsent(cand, cid)
                        byTokens.putIfAbsent(EpgNorm.tokenKey(cand), cid)
                        bySquash.putIfAbsent(EpgNorm.squash(cand), cid)
                        byDepl.putIfAbsent(EpgNorm.deplKey(cand), cid)
                        coresById.getOrPut(cid) { mutableSetOf() }.add(cand)
                    }
                }
            }
            val bucket = HashMap<String, MutableList<String>>()
            for (c in byCore.keys) {
                if (c.isNotEmpty()) bucket.getOrPut(c.substringBefore(' ')) { mutableListOf() }.add(c)
            }
            return EpgChannelIndex(byTvg, byCore, byTokens, bySquash, byDepl, coresById, bucket)
        }
    }
}
