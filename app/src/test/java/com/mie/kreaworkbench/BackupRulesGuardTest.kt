package com.mie.kreaworkbench

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份排除守卫（r45）。
 *
 * `allowBackup="true"` 时系统会把 files/datastore/krea_settings.preferences_pb 一起备份，
 * 里面存着服务器地址 + 账号密码（密文来自 Keystore，Keystore 异常时退成 plain: 明文）。
 * Keystore 密钥不随备份走，恢复到新机器也解不开——排除它没有功能损失。
 * 这里用源码级守卫锁住：manifest 两个属性 + 两份 xml 的排除路径 + DataStore 名。
 */
class BackupRulesGuardTest {

    /** 测试 JVM 的工作目录是 app/（Gradle 默认），所以先按 app/ 找，再回退到仓库根。 */
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("..", relative), File("../..", relative))
        val f = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到源文件，试过：${candidates.joinToString { it.absolutePath }}",
            f != null,
        )
        return f!!.readText()
    }

    private fun manifest() = source("app/src/main/AndroidManifest.xml")

    @Test
    fun `manifest keeps allowBackup and points at both rules`() {
        val m = manifest()
        assertTrue("allowBackup 应保持 true（画廊/提示词库仍可备份）", m.contains("android:allowBackup=\"true\""))
        assertTrue(
            "manifest 应声明 dataExtractionRules（API 31+）",
            m.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertTrue(
            "manifest 应声明 fullBackupContent（API ≤ 30）",
            m.contains("android:fullBackupContent=\"@xml/full_backup_content\""),
        )
    }

    @Test
    fun `both rules exclude the settings datastore`() {
        val der = source("app/src/main/res/xml/data_extraction_rules.xml")
        val fbc = source("app/src/main/res/xml/full_backup_content.xml")
        for (xml in listOf(der, fbc)) {
            assertTrue("备份排除里应有 krea_settings.preferences_pb", xml.contains("krea_settings.preferences_pb"))
        }
        assertTrue(
            "dataExtractionRules 应同时覆盖 cloud-backup 与 device-transfer",
            der.contains("<cloud-backup>") && der.contains("<device-transfer>"),
        )
    }

    @Test
    fun `datastore name unchanged - exclusion paths must follow it`() {
        val store = source("app/src/main/java/com/mie/kreaworkbench/data/settings/SettingsStore.kt")
        assertTrue(
            "DataStore 改名的话，两份备份排除 xml 的路径要跟着改",
            store.contains("preferencesDataStore(\"krea_settings\")"),
        )
    }
}
