package com.mie.kreaworkbench.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** r41：开 VPN 时挑扫描网段的纯逻辑。 */
class LanSegmentPickTest {

    private fun ip(s: String): Int {
        val b = s.split(".").map { it.toInt() }
        return ipv4BytesToInt(byteArrayOf(b[0].toByte(), b[1].toByte(), b[2].toByte(), b[3].toByte()))
    }

    private fun cand(
        wifi: Boolean = false,
        ethernet: Boolean = false,
        vpn: Boolean = false,
        ipv4: Int? = null,
        prefix: Int = 24,
        active: Boolean = false,
    ) = LanNetCandidate(wifi, ethernet, vpn, ipv4, prefix, active)

    @Test
    fun vpnWithWifiCapabilityDoesNotShadowRealWifi() {
        // VPN 是 active、也带 wifi 能力（部分 VPN 会把底层网络能力带上）——必须选真 Wi-Fi
        val tun = cand(wifi = true, vpn = true, ipv4 = ip("198.18.0.1"), active = true)
        val wifi = cand(wifi = true, ipv4 = ip("192.168.1.37"))
        val seg = pickLanSegment(listOf(tun, wifi))!!
        assertEquals(ip("192.168.1.37"), seg.selfIp)
        assertFalse(seg.viaFallback)
    }

    @Test
    fun ethernetPickedWhenNoWifi() {
        val eth = cand(ethernet = true, ipv4 = ip("10.0.0.5"))
        assertEquals(ip("10.0.0.5"), pickLanSegment(listOf(eth))!!.selfIp)
    }

    @Test
    fun wifiWithoutIpv4Skipped() {
        val noIp = cand(wifi = true, ipv4 = null, active = true)
        val eth = cand(ethernet = true, ipv4 = ip("10.0.0.5"))
        assertEquals(ip("10.0.0.5"), pickLanSegment(listOf(noIp, eth))!!.selfIp)
        // 只剩没 IP 的 Wi-Fi：①②③都不满足 → null
        assertNull(pickLanSegment(listOf(noIp)))
    }

    @Test
    fun fallsBackToActiveVpnNetworkWithWifiCapability() {
        // 没有任何非 VPN 候选，但 active 的 VPN 网络带 wifi 能力且有 IPv4 → 回退（= r39 旧行为）
        val tun = cand(wifi = true, vpn = true, ipv4 = ip("26.26.26.26"), active = true)
        val seg = pickLanSegment(listOf(tun))!!
        assertEquals(ip("26.26.26.26"), seg.selfIp)
        assertTrue(seg.viaFallback)
    }

    @Test
    fun pureVpnReturnsNull() {
        // 纯 VPN（不带 Wi-Fi/以太网能力）→ 没得扫
        val tun = cand(vpn = true, ipv4 = ip("10.8.0.2"), active = true)
        assertNull(pickLanSegment(listOf(tun)))
    }

    @Test
    fun prefixPassedThroughUnchanged() {
        val wifi = cand(wifi = true, ipv4 = ip("192.168.1.37"), prefix = 25)
        assertEquals(25, pickLanSegment(listOf(wifi))!!.prefix)
    }

    @Test
    fun multipleWifiCandidatesPreferActive() {
        val a = cand(wifi = true, ipv4 = ip("192.168.1.10"))
        val b = cand(wifi = true, ipv4 = ip("192.168.50.20"), active = true)
        assertEquals(ip("192.168.50.20"), pickLanSegment(listOf(a, b))!!.selfIp)
    }

    // ---- r42：network 字段原样带出 + usableOrNull 预检 ----

    @Test
    fun networkDefaultsToNullAndDoesNotAffectOldAssertions() {
        // 旧调用方不传 network：直接分支和回退分支都带出 null，已有挑选逻辑不受影响
        val wifi = cand(wifi = true, ipv4 = ip("192.168.1.37"))
        val seg = pickLanSegment(listOf(wifi))!!
        assertNull(seg.network)
        val tun = cand(wifi = true, vpn = true, ipv4 = ip("26.26.26.26"), active = true)
        assertNull(pickLanSegment(listOf(tun))!!.network)
    }

    @Test
    fun usableOrNullPassesThroughOnlyWhenCheckHolds() {
        assertEquals("x", usableOrNull("x") { true })
        assertNull(usableOrNull("x") { false })
        assertNull(usableOrNull(null as String?) { true })
        // check 抛异常 → 当作不可用，不往外抛
        assertNull(usableOrNull("x") { error("boom") })
    }
}
