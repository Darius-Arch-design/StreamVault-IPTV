package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.data.security.CredentialCrypto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositoryTitloviCredentialsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @After
    fun tearDown() {
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @Test
    fun `Titlovi credentials round trip through credential encryption and can be cleared`() = runBlocking {
        val crypto = RecordingCredentialCrypto()
        val repository = PreferencesRepository(
            context = context,
            channelPreferenceDao = mock<ChannelPreferenceDao>(),
            searchHistoryDao = mock<SearchHistoryDao>(),
            corruptionRecovery = mock<PreferencesCorruptionRecovery>(),
            credentialCrypto = crypto
        )

        repository.setTitloviCredentials("  korisnik  ", "lozinka")

        assertThat(crypto.encryptedValues).containsExactly("korisnik", "lozinka").inOrder()
        assertThat(repository.getTitloviCredentials()?.username).isEqualTo("korisnik")
        assertThat(repository.getTitloviCredentials()?.password).isEqualTo("lozinka")

        repository.clearTitloviCredentials()
        assertThat(repository.getTitloviCredentials()).isNull()
    }

    private class RecordingCredentialCrypto : CredentialCrypto {
        val encryptedValues = mutableListOf<String>()

        override fun encryptIfNeeded(value: String): String {
            encryptedValues += value
            return "encrypted:$value"
        }

        override fun decryptIfNeeded(value: String): String = value.removePrefix("encrypted:")
    }
}
