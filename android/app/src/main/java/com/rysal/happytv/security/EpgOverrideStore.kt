package com.rysal.happytv.security

/**
 * Encrypted home for a user-typed XMLTV link (#18). The link can carry
 * credentials, so the plain settings DataStore only ever holds a marker.
 */
interface EpgOverrideStore {
    suspend fun get(): String?

    suspend fun set(value: String?)
}

/** Non-persistent store for tests and previews. */
class InMemoryEpgOverrideStore(@Volatile var value: String? = null) : EpgOverrideStore {
    override suspend fun get(): String? = value

    override suspend fun set(value: String?) {
        this.value = value
    }
}
