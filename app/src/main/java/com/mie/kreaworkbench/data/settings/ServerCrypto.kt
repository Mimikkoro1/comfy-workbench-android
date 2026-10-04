package com.mie.kreaworkbench.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 服务器密码本地加密：AndroidKeyStore 里的 AES/GCM 密钥，密文格式 "enc1:" + base64(iv + ct)。
 * Keystore 不可用（厂商 ROM 异常等）时退回 "plain:" 前缀明文——功能优先，且密钥从不离开 keystore。
 * 密文只进 DataStore，不打日志。
 */
object ServerCrypto {
    private const val ALIAS = "krea_server_pwd"
    private const val ENC_PREFIX = "enc1:"
    private const val PLAIN_PREFIX = "plain:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            ENC_PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            PLAIN_PREFIX + plain
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return when {
            stored.startsWith(ENC_PREFIX) -> try {
                val raw = Base64.decode(stored.substring(ENC_PREFIX.length), Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
                String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            stored.startsWith(PLAIN_PREFIX) -> stored.substring(PLAIN_PREFIX.length)
            else -> stored
        }
    }
}
