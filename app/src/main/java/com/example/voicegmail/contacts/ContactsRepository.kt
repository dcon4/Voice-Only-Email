package com.example.voicegmail.contacts

import android.util.Log
import com.example.voicegmail.auth.AuthRepository
import com.example.voicegmail.debug.DebugLogger
import com.example.voicegmail.gmail.EmailItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceGmail.Contacts"

/**
 * In-memory cache + paginated fetcher for the user's Google Contacts via the
 * People API.  Kept deliberately simple (no Room/DataStore persistence) — the
 * full contact set is re-fetched on each cold start, then refreshed at most
 * once per [REFRESH_TTL_MS] while the process is alive.
 *
 * Combined with [ContactMatcher.extractSendersFromInbox], this is the source
 * of truth for the voice-driven recipient matcher in the compose flow.
 */
@Singleton
class ContactsRepository @Inject constructor(
    private val peopleApiService: PeopleApiService,
    private val authRepository: AuthRepository
) {
    @Volatile private var cached: List<Contact> = emptyList()
    @Volatile private var lastFetchAt: Long = 0L
    private val fetchMutex = Mutex()

    /**
     * Returns the union of inbox-derived senders and People-API contacts,
     * deduplicated by lower-cased email address.  Inbox senders win the
     * dedupe tie-break (they're more likely to be the person the user
     * actually wants to reply to right now), but People-API contacts
     * contribute the rest of the user's address book.
     *
     * If a fetch hasn't completed yet, this returns just the inbox senders —
     * the People API call is fire-and-forget on the first call so the UI
     * never blocks waiting for a network round-trip.
     */
    suspend fun combinedContacts(inboxEmails: List<EmailItem>): List<Contact> {
        ensureFreshCache()
        val inboxContacts = ContactMatcher.extractSendersFromInbox(inboxEmails)
        if (cached.isEmpty()) return inboxContacts
        val byEmail = HashMap<String, Contact>(cached.size + inboxContacts.size)
        // People API first, then Inbox overwrites — so the inbox's recency
        // wins for tie-break, and the People-API display name is the fallback.
        for (c in cached) byEmail[c.email.lowercase()] = c
        for (c in inboxContacts) byEmail[c.email.lowercase()] = c
        return byEmail.values.toList()
    }

    private suspend fun ensureFreshCache() {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - lastFetchAt < REFRESH_TTL_MS) return
        fetchMutex.withLock {
            // Re-check after acquiring the lock so concurrent callers don't
            // all hit the network.
            val now2 = System.currentTimeMillis()
            if (cached.isNotEmpty() && now2 - lastFetchAt < REFRESH_TTL_MS) return
            refresh()
        }
    }

    /**
     * Forces a full refresh from the People API.  Used at app startup after
     * the contacts scope has just been granted, and as the on-demand fallback
     * when the in-memory cache TTL expires.
     */
    suspend fun refresh() {
        val token = authRepository.accessTokenFlow.first()
        if (token.isNullOrBlank()) {
            DebugLogger.log(TAG, "refresh: no access token — skipping")
            return
        }
        try {
            val collected = ArrayList<Contact>()
            var pageToken: String? = null
            var pages = 0
            do {
                val resp = callWithRefresh(token) { auth ->
                    peopleApiService.listConnections(
                        auth = auth,
                        pageToken = pageToken
                    )
                }
                resp.connections?.forEach { person ->
                    val name = person.names?.firstOrNull()?.displayName
                        ?: listOfNotNull(
                            person.names?.firstOrNull()?.givenName,
                            person.names?.firstOrNull()?.familyName
                        ).joinToString(" ").ifBlank { null }
                    val email = person.emailAddresses?.firstOrNull { !it.value.isNullOrBlank() }?.value
                    if (!email.isNullOrBlank()) {
                        collected += Contact(
                            displayName = name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'),
                            email = email,
                            source = Contact.Source.People
                        )
                    }
                }
                pageToken = resp.nextPageToken
                pages++
                // Safety cap — don't loop forever even if the API misbehaves.
            } while (!pageToken.isNullOrBlank() && pages < MAX_PAGES)
            cached = collected
            lastFetchAt = System.currentTimeMillis()
            DebugLogger.log(TAG, "refresh: loaded ${collected.size} contacts across $pages page(s)")
        } catch (e: HttpException) {
            // 401 / 403 most likely means the user hasn't consented to the
            // contacts.readonly scope yet, OR the People API isn't enabled
            // in the Cloud project.  Either way, fall back gracefully to
            // inbox-only matching — the compose flow still works, just with
            // a smaller candidate pool.
            Log.w(TAG, "People API call failed: ${e.code()} ${e.message()}")
            DebugLogger.log(TAG, "refresh: HTTP ${e.code()} — falling back to inbox-only contacts")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error fetching contacts", e)
            DebugLogger.log(TAG, "refresh: failed (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private suspend fun <T> callWithRefresh(
        accessToken: String,
        block: suspend (auth: String) -> T
    ): T {
        val firstAuth = "Bearer $accessToken"
        return try {
            block(firstAuth)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                val newToken = authRepository.refreshAccessToken()
                if (newToken != null) block("Bearer $newToken") else throw e
            } else throw e
        }
    }

    private companion object {
        /** Refetch People API at most once per 30 minutes per process. */
        const val REFRESH_TTL_MS = 30L * 60L * 1000L

        /** Hard upper bound on pages walked — protects against runaway loops. */
        const val MAX_PAGES = 50
    }
}
