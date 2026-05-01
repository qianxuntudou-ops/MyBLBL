package com.tutu.myblbl.network.http

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

class DebugNetworkEventListener : EventListener() {

    private data class TraceState(
        val callStartMs: Long = 0L,
        var dnsStartMs: Long = 0L,
        var dnsEndMs: Long = 0L,
        var connectStartMs: Long = 0L,
        var connectEndMs: Long = 0L,
        var secureConnectStartMs: Long = 0L,
        var secureConnectEndMs: Long = 0L,
        var requestHeadersStartMs: Long = 0L,
        var responseHeadersStartMs: Long = 0L,
        var responseHeadersEndMs: Long = 0L,
        var responseBodyEndMs: Long = 0L,
        var callEndMs: Long = 0L,
        var callFailed: Boolean = false,
        var errorMessage: String? = null
    )

    private val traces = ConcurrentHashMap<Call, TraceState>()

    override fun callStart(call: Call) {
        traces[call] = TraceState(callStartMs = System.currentTimeMillis())
    }

    override fun dnsStart(call: Call, domainName: String) {
        traces[call]?.let { it.dnsStartMs = System.currentTimeMillis() }
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        traces[call]?.let { it.dnsEndMs = System.currentTimeMillis() }
    }

    override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
        traces[call]?.let { it.connectStartMs = System.currentTimeMillis() }
    }

    override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        traces[call]?.let { it.connectEndMs = System.currentTimeMillis() }
    }

    override fun connectFailed(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: java.io.IOException) {
        traces[call]?.let {
            it.connectEndMs = System.currentTimeMillis()
            it.callFailed = true
            it.errorMessage = ioe.message
        }
    }

    override fun secureConnectStart(call: Call) {
        traces[call]?.let { it.secureConnectStartMs = System.currentTimeMillis() }
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        traces[call]?.let { it.secureConnectEndMs = System.currentTimeMillis() }
    }

    override fun requestHeadersStart(call: Call) {
        traces[call]?.let { it.requestHeadersStartMs = System.currentTimeMillis() }
    }

    override fun responseHeadersStart(call: Call) {
        traces[call]?.let { it.responseHeadersStartMs = System.currentTimeMillis() }
    }

    override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
        traces[call]?.let { it.responseHeadersEndMs = System.currentTimeMillis() }
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        traces[call]?.let { it.responseBodyEndMs = System.currentTimeMillis() }
    }

    override fun callEnd(call: Call) {
        val trace = traces.remove(call) ?: return
        trace.callEndMs = System.currentTimeMillis()
    }

    override fun callFailed(call: Call, ioe: java.io.IOException) {
        val trace = traces.remove(call) ?: return
        trace.callEndMs = System.currentTimeMillis()
        trace.callFailed = true
        trace.errorMessage = ioe.message
    }

    class Factory : EventListener.Factory {
        private val singleton = DebugNetworkEventListener()
        override fun create(call: Call): EventListener = singleton
    }
}
