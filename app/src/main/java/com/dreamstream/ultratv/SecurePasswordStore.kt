package com.dreamstream.ultratv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecurePasswordStore {
    private const val ALIAS = "dreamstream_tv_login_key_v1"
    private const val FILE = "dreamstream_secure_login"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }

    fun save(context: Context, password: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())

        val encrypted = cipher.doFinal(
            password.toByteArray(Charsets.UTF_8)
        )

        context.getSharedPreferences(
            FILE,
            Context.MODE_PRIVATE
        ).edit()
            .putString(
                "iv",
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                )
            )
            .putString(
                "ciphertext",
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )
            )
            .apply()
    }

    fun read(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(
                FILE,
                Context.MODE_PRIVATE
            )

            val iv = prefs.getString("iv", null)
                ?: return null

            val encrypted = prefs.getString(
                "ciphertext",
                null
            ) ?: return null

            val cipher = Cipher.getInstance(TRANSFORM)

            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(
                    128,
                    Base64.decode(
                        iv,
                        Base64.NO_WRAP
                    )
                )
            )

            String(
                cipher.doFinal(
                    Base64.decode(
                        encrypted,
                        Base64.NO_WRAP
                    )
                ),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(
            FILE,
            Context.MODE_PRIVATE
        ).edit().clear().apply()
    }
}
