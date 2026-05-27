package com.example.voicegmail.auth

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped bus that bridges MainActivity.onNewIntent (the OAuth redirect receiver)
 * with InboxViewModel (which exchanges the authorization code for tokens).
 *
 * Using MutableStateFlow<Uri?> guarantees that even if InboxViewModel starts collecting
 * slightly after MainActivity posts the redirect (e.g. during process-death restart),
 * the URI is not lost — StateFlow replays its current value to new subscribers.
 */
@Singleton
class OAuthRedirectBus @Inject constructor() {
    private val _redirectUri = MutableStateFlow<Uri?>(null)
    val redirectUri: StateFlow<Uri?> = _redirectUri.asStateFlow()

    /** Called by MainActivity when an OAuth redirect intent arrives. */
    fun post(uri: Uri) {
        _redirectUri.value = uri
    }

    /** Called by InboxViewModel after the redirect has been handled. */
    fun consume() {
        _redirectUri.value = null
    }
}
