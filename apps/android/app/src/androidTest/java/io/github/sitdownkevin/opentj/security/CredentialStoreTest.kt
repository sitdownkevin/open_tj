package io.github.sitdownkevin.opentj.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sitdownkevin.opentj.core.Credentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    @Test fun keystoreRoundTripUsesFreshIvAndDeletesRememberedPassword() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialStore(context)
        val preferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        try {
            val credentials = Credentials("instrumentation-user", "test-only-password")
            store.save(credentials, true)
            val firstIv = preferences.getString("iv", null)
            assertEquals(credentials.password, store.load().password)
            assertFalse(preferences.all.values.any { it.toString().contains(credentials.password) })
            store.save(credentials, true)
            assertNotEquals(firstIv, preferences.getString("iv", null))
            store.forgetPassword()
            assertFalse(preferences.contains("password"))
            assertFalse(preferences.contains("iv"))
            assertEquals("", store.load().password)
        } finally { preferences.edit().clear().commit() }
    }
}
