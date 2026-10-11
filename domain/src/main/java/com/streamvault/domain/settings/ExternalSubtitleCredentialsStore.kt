package com.streamvault.domain.settings

data class TitloviCredentials(
    val username: String,
    val password: String
)

/** Secure credential boundary used by settings and external subtitle providers. */
interface ExternalSubtitleCredentialsStore {
    suspend fun getTitloviCredentials(): TitloviCredentials?

    suspend fun setTitloviCredentials(username: String, password: String)

    suspend fun clearTitloviCredentials()
}
