package com.twtrubiks.imgurshare.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 使用者設定。Client-ID 不加密：它本來就不是秘密，而且 Keystore 加密的資料備份還原到新手機會解不開。
 */
class Settings(private val dataStore: DataStore<Preferences>) {

    private val preferences = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val clientId: Flow<String> = preferences.map { it[CLIENT_ID].orEmpty() }

    val autoCopy: Flow<Boolean> = preferences.map { it[AUTO_COPY] ?: true }

    suspend fun setClientId(clientId: String) {
        dataStore.edit { it[CLIENT_ID] = clientId.trim() }
    }

    suspend fun setAutoCopy(enabled: Boolean) {
        dataStore.edit { it[AUTO_COPY] = enabled }
    }

    private companion object {
        val CLIENT_ID = stringPreferencesKey("client_id")
        val AUTO_COPY = booleanPreferencesKey("auto_copy")
    }
}
