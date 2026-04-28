package com.tutu.myblbl.network

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object WbiGenerator {

    private var cachedOriginKey: String? = null
    private var cachedMixinKey: String? = null
    private var cachedBRet: String? = null

    fun ensureBRet(): String {
        cachedBRet?.let { return it }
        val rand = ByteArray(44)
        SecureRandom().nextBytes(rand)
        rand[36] = 0; rand[37] = 73; rand[38] = 69; rand[39] = 78; rand[40] = 68; rand[41] = 0xAE.toByte(); rand[42] = 0x42; rand[43] = 0x60
        val b64 = Base64.encodeToString(rand, Base64.NO_WRAP)
        val result = b64.takeLast(80)
        cachedBRet = result
        return result
    }

    fun getCachedBRet(): String? = cachedBRet

    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    fun generateWbiParams(
        params: Map<String, String>,
        imgKey: String,
        subKey: String
    ): Map<String, String> {
        if (imgKey.isBlank() || subKey.isBlank()) {
            return params
        }

        val originKey = imgKey + subKey
        synchronized(this) {
            if (originKey != cachedOriginKey) {
                cachedOriginKey = null
                cachedMixinKey = null
            }
        }
        val mixinKey = getMixinKey(originKey)
        val wts = System.currentTimeMillis() / 1000

        val withWts = params.toMutableMap()
        withWts["wts"] = wts.toString()

        val sorted = withWts.entries.sortedBy { it.key }
            .associate { it.key to filterValue(it.value) }
        val query = sorted.entries
            .joinToString("&") { (k, v) -> "${percentEncodeUtf8(k)}=${percentEncodeUtf8(v)}" }
        val wRid = md5(query + mixinKey)

        val result = params.toMutableMap()
        result["wts"] = wts.toString()
        result["w_rid"] = wRid
        return result
    }

    private fun getMixinKey(originKey: String): String {
        synchronized(this) {
            if (originKey == cachedOriginKey && cachedMixinKey != null) {
                return cachedMixinKey!!
            }
        }
        val sb = StringBuilder()
        for (i in mixinKeyEncTab.indices) {
            val index = mixinKeyEncTab[i]
            if (index < originKey.length) {
                sb.append(originKey[index])
            }
        }
        val result = sb.toString().take(32)
        synchronized(this) {
            cachedOriginKey = originKey
            cachedMixinKey = result
        }
        return result
    }

    private fun filterValue(v: String): String = v.filterNot { it in "!\'()*" }

    private fun percentEncodeUtf8(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved = c in 'a'.code..'z'.code ||
                c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // dm_* anti-bot params from browser capture
    fun getSpaceDmParams(): Map<String, String> = mapOf(
        "dm_img_list" to "[]",
        "dm_img_str" to "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ",
        "dm_cover_img_str" to "QU5HTEUgKEludGVsLCBJbnRlbChSKSBJcmlzKFIpIFhlIEdyYXBoaWNzICgweDAwMDA0NkE2KSBEaXJlY3QzRDExIHZzXzVfMCBwc181XzAsIEQzRDExKUdvb2dsZSBJbmMuIChJbnRlbC",
        "dm_img_inter" to "{\"ds\":[],\"wh\":[5032,6004,10],\"of\":[425,850,425]}"
    )

    fun extractKeyFromUrl(url: String): String {
        val wbiIndex = url.indexOf("wbi/")
        if (wbiIndex == -1) return ""
        val startIndex = wbiIndex + 4
        val endIndex = url.indexOf(".", startIndex)
        return if (endIndex > startIndex) {
            url.substring(startIndex, endIndex)
        } else {
            ""
        }
    }
}
