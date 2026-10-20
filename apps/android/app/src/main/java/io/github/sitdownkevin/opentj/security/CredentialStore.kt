package io.github.sitdownkevin.opentj.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.sitdownkevin.opentj.core.Credentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SavedAccount(
    val username: String = "",
    val password: String = "",
    val method: String = "LDAP3",
    val remember: Boolean = false,
)

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val associatedData = "${context.packageName}:credentials:v1".toByteArray()

    @Synchronized
    fun load(): SavedAccount {
        val username = preferences.getString("username", "") ?: ""
        val method = preferences.getString("method", "LDAP3").takeIf { it in Credentials.METHODS } ?: "LDAP3"
        val remember = preferences.getBoolean("remember", false)
        if (!remember) return SavedAccount(username = username, method = method)
        try {
            val encrypted = preferences.getString("password", null) ?: error("Missing ciphertext")
            val iv = preferences.getString("iv", null) ?: error("Missing IV")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, decode(iv)))
            cipher.updateAAD(associatedData)
            val password = cipher.doFinal(decode(encrypted)).toString(Charsets.UTF_8)
            return SavedAccount(username, password, method, true)
        } catch (_: Exception) {
            forgetPassword()
            throw IllegalStateException("已保存的密码无法读取，请重新输入")
        }
    }

    @Synchronized
    fun save(credentials: Credentials, remember: Boolean) {
        val editor = preferences.edit()
            .putString("username", credentials.username)
            .putString("method", credentials.method)
            .putBoolean("remember", remember)
        if (remember) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(associatedData)
            editor.putString("password", encode(cipher.doFinal(credentials.password.toByteArray(Charsets.UTF_8))))
            editor.putString("iv", encode(cipher.iv))
        } else {
            editor.remove("password").remove("iv")
        }
        check(editor.commit()) { "无法保存账号设置" }
    }

    @Synchronized
    fun forgetPassword() {
        check(preferences.edit().remove("password").remove("iv").putBoolean("remember", false).commit()) {
            "无法删除已保存的密码"
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val ALIAS = "opentj.credentials.v1"
    }
}
