package edu.colorado.rrassist.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

enum class SecretKey(val ref: String) {
    API_KEY("API_KEY"),
}

object Secrets {
    private fun attr(key: SecretKey) = CredentialAttributes("RRAssist/${key.ref}")

    fun save(key: SecretKey, secret: String) {
        PasswordSafe.instance.setPassword(attr(key), secret)
    }

    fun load(key: SecretKey): String? {
        return PasswordSafe.instance.getPassword(attr(key))
    }
}
