package com.mie.kreaworkbench.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** r41：连接失败分类。 */
class ConnectionDiagnosisTest {

    private fun classify(
        stage: ConnStage = ConnStage.HTTP,
        cause: Throwable? = null,
        code: Int? = null,
        sdk: Int = 36,
        granted: Boolean = true,
        local: Boolean = true,
    ) = classifyConnectionError(stage, cause, code, sdk, granted, local)

    // ---- 异常分类（SDK 36，无局域网特例） ----

    @Test
    fun timeoutException() {
        assertEquals(ConnErrorKind.TIMEOUT, classify(cause = SocketTimeoutException("connect timed out")))
    }

    @Test
    fun refusedException() {
        assertEquals(
            ConnErrorKind.REFUSED,
            classify(cause = ConnectException("failed to connect to /10.0.0.52:8188 (Connection refused)")),
        )
    }

    @Test
    fun unknownHostException() {
        assertEquals(ConnErrorKind.UNKNOWN_HOST, classify(cause = UnknownHostException("mypc.local")))
    }

    @Test
    fun noRouteException() {
        assertEquals(ConnErrorKind.NO_ROUTE, classify(cause = NoRouteToHostException("Network unreachable")))
    }

    @Test
    fun tlsException() {
        assertEquals(ConnErrorKind.TLS, classify(cause = SSLHandshakeException("Handshake failed")))
    }

    // ---- r41 特例：安卓 17 未授权时超时 = 被系统拦截 ----

    @Test
    fun blockedWhenSdk37DeniedAndTimeout() {
        assertEquals(
            ConnErrorKind.LOCAL_NET_BLOCKED,
            classify(cause = SocketTimeoutException("connect timed out"), sdk = 37, granted = false, local = true),
        )
    }

    @Test
    fun plainTimeoutWhenGranted() {
        assertEquals(
            ConnErrorKind.TIMEOUT,
            classify(cause = SocketTimeoutException("connect timed out"), sdk = 37, granted = true, local = true),
        )
    }

    @Test
    fun plainTimeoutForPublicHost() {
        assertEquals(
            ConnErrorKind.TIMEOUT,
            classify(cause = SocketTimeoutException("connect timed out"), sdk = 37, granted = false, local = false),
        )
    }

    @Test
    fun refusedIsNotBlocked() {
        assertEquals(
            ConnErrorKind.REFUSED,
            classify(
                cause = ConnectException("failed to connect (Connection refused)"),
                sdk = 37, granted = false, local = true,
            ),
        )
    }

    @Test
    fun sdk36Unaffected() {
        assertEquals(
            ConnErrorKind.TIMEOUT,
            classify(cause = SocketTimeoutException("connect timed out"), sdk = 36, granted = false, local = true),
        )
    }

    // ---- HTTP / CONTENT 阶段 ----

    @Test
    fun httpCodes() {
        assertEquals(ConnErrorKind.AUTH_REQUIRED, classify(code = 401))
        assertEquals(ConnErrorKind.AUTH_REQUIRED, classify(code = 403))
        assertEquals(ConnErrorKind.NOT_FOUND, classify(code = 404))
        assertEquals(ConnErrorKind.HTTP_OTHER, classify(code = 500))
        // 2xx 但内容不是 ComfyUI
        assertEquals(ConnErrorKind.NOT_COMFY, classify(stage = ConnStage.CONTENT, code = 200))
    }

    // ---- WS 阶段 ----

    @Test
    fun wsCodes() {
        assertEquals(ConnErrorKind.WS_AUTH, classify(stage = ConnStage.WS, code = 401))
        assertEquals(ConnErrorKind.WS_REJECTED, classify(stage = ConnStage.WS, code = 403))
        assertEquals(ConnErrorKind.WS_REJECTED, classify(stage = ConnStage.WS, code = 400))
        assertEquals(
            ConnErrorKind.WS_FAILED,
            classify(stage = ConnStage.WS, cause = RuntimeException("connection reset")),
        )
    }

    // ---- r41.1：WS 握手探测结局 → ConnError ----

    @Test
    fun wsProbeOpenedMeansNoError() {
        assertNull(
            wsProbeError(WsHandshakeOutcome.Opened, sdkInt = 36, hasLanPermission = true, hostIsLocal = true),
        )
    }

    @Test
    fun wsProbeTimeoutIsWsFailed() {
        // outcome == null = 6 秒内没等到任何结局（超时）；只有这种情况才报 WS_FAILED
        val e = wsProbeError(null, sdkInt = 36, hasLanPermission = true, hostIsLocal = true)
        assertEquals(ConnErrorKind.WS_FAILED, e?.kind)
        assertNull(e?.httpCode)
    }

    @Test
    fun wsProbeRejected401IsWsAuth() {
        val e = wsProbeError(
            WsHandshakeOutcome.Failed(ProtocolException("Expected HTTP 101 response but was '401 Unauthorized'"), 401),
            sdkInt = 36, hasLanPermission = true, hostIsLocal = true,
        )
        assertEquals(ConnErrorKind.WS_AUTH, e?.kind)
        assertEquals(401, e?.httpCode)
    }

    @Test
    fun wsProbeRejected403CodeFromMessage() {
        // OkHttp 有时把状态码只留在异常消息里（response 为 null）
        val e = wsProbeError(
            WsHandshakeOutcome.Failed(ProtocolException("Expected HTTP 101 response but was '403 Forbidden'"), null),
            sdkInt = 36, hasLanPermission = true, hostIsLocal = true,
        )
        assertEquals(ConnErrorKind.WS_REJECTED, e?.kind)
        assertEquals(403, e?.httpCode)
    }

    // ---- r41.1：测试结果 → ping 行（版本/延迟不进 ping 行，摘要行另显） ----

    private fun report(
        error: ConnError? = null,
        wsError: ConnError? = null,
    ) = ConnectionReport(
        httpOk = error == null,
        version = if (error == null) "ComfyUI 0.3.10" else "",
        latencyMs = if (error == null) 22 else 12,
        error = error,
        wsError = wsError,
        detail = "",
    )

    @Test
    fun pingLineSuccessNoWsErrorIsEmpty() {
        assertNull(pingLineError(report()))
    }

    @Test
    fun pingLineSuccessWithWsErrorIsWsHint() {
        val ws = ConnError(ConnErrorKind.WS_AUTH, 401, "Expected HTTP 101 response but was '401 Unauthorized'")
        assertEquals(ws, pingLineError(report(wsError = ws)))
    }

    @Test
    fun pingLineFailureIsMainError() {
        val err = ConnError(ConnErrorKind.REFUSED, null, "ConnectException: Connection refused")
        assertEquals(err, pingLineError(report(error = err)))
    }

    // ---- isComfyStats ----

    @Test
    fun realSystemStatsSample() {
        val body = """
            {"system":{"os":"posix","comfyui_version":"0.3.10","python_version":"3.12.0"},
             "devices":[{"name":"NVIDIA GeForce RTX 4090","type":"cuda","index":0}]}
        """.trimIndent()
        assertTrue(isComfyStats(200, body))
    }

    @Test
    fun minimalSystemObject() {
        assertTrue(isComfyStats(200, "{\"system\":{}}"))
    }

    @Test
    fun notComfyStats() {
        assertFalse(isComfyStats(200, "<html>hello</html>"))
        assertFalse(isComfyStats(200, ""))
        assertFalse(isComfyStats(200, "{}"))
        assertFalse(isComfyStats(404, "{\"system\":{}}"))
    }
}
