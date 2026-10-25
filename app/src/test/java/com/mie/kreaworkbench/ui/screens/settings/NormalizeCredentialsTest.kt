package com.mie.kreaworkbench.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 落库前的凭据规范化：账号去首尾空白，密码原样。 */
class NormalizeCredentialsTest {

    @Test
    fun trimsUserOnly() {
        assertEquals("a" to "p", normalizeCredentials(" a ", "p"))
        assertEquals("a" to "", normalizeCredentials("  a\t", ""))
    }

    @Test
    fun keepsPassVerbatim() {
        // 密码里可以有空格，不做 trim
        assertEquals("a" to " p w ", normalizeCredentials("a", " p w "))
        assertEquals("" to " ", normalizeCredentials("", " "))
    }
}
