package com.tutu.myblbl.network.security

import java.security.MessageDigest

object AppSignUtils {
    const val TV_APP_KEY = "4409e2ce8ffd12b8"
    private const val TV_APP_SEC = "59b43e04ad6965f34319062b478f83dd"

    fun signForTvLogin(params: Map<String, String>): Map<String, String> {
        return sign(params, TV_APP_SEC)
    }

    fun signForAppApi(params: Map<String, String>): Map<String, String> {
        return sign(params, TV_APP_SEC)
    }

    private fun sign(params: Map<String, String>, appSec: String): Map<String, String> {
        val sortedParams = params.toSortedMap()
        val queryString = sortedParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sign = md5(queryString + appSec)
        return sortedParams + ("sign" to sign)
    }

    fun getTimestamp(): Long = System.currentTimeMillis() / 1000

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
