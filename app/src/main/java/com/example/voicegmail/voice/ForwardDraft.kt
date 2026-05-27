package com.example.voicegmail.voice

import javax.inject.Inject
import javax.inject.Singleton

/** Pre-filled fields for a forwarded email being composed. */
data class ForwardData(
    val to: String,
    val subject: String,
    val body: String
)

/**
 * Singleton bridge that carries a forward draft from [InboxViewModel]
 * (where the recipient is captured by voice) to [ComposeViewModel]
 * (where the send flow runs). Navigation arguments are unsuitable for
 * body text due to length limits, so we use this in-memory holder.
 *
 * The draft is consumed (read + cleared) in one atomic call so it is
 * never accidentally applied twice.
 */
@Singleton
class ForwardDraft @Inject constructor() {

    @Volatile
    var pending: ForwardData? = null

    fun set(to: String, subject: String, body: String) {
        pending = ForwardData(to = to, subject = subject, body = body)
    }

    /** Returns the pending draft and clears it, or null if none is waiting. */
    fun consume(): ForwardData? = pending.also { pending = null }
}
