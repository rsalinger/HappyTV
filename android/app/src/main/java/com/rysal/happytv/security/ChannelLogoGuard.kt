package com.rysal.happytv.security

/**
 * The signed-in username and password, kept in memory only so a channel logo URL that embeds either
 * is never requested from a third-party host (#139). [SecuredCredentialsStore] keeps it current.
 */
object ChannelLogoGuard {
    @Volatile
    var secrets: List<String> = emptyList()
        private set

    fun update(username: String?, password: String?) {
        secrets = listOfNotNull(username?.takeIf { it.isNotEmpty() }, password?.takeIf { it.isNotEmpty() })
    }
}
