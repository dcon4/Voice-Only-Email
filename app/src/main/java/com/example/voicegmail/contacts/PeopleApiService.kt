package com.example.voicegmail.contacts

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Retrofit interface for the Google People API (read-only contacts).
 *
 * Uses its own Retrofit instance with baseUrl `https://people.googleapis.com/`
 * (see [com.example.voicegmail.di.AppModule]).  Auth is injected per-call as a
 * `Bearer <token>` header, matching the pattern used by [GmailApiService].
 */
interface PeopleApiService {

    /**
     * Lists the authenticated user's contacts ("connections").
     *
     * - [personFields] must be specified or the API rejects the call.  We only
     *   ask for names + email addresses to keep responses small.
     * - [pageSize] up to 1000; 200 is a friendly default.
     * - Pass [pageToken] to walk additional pages.
     * - First call should pass `requestSyncToken = true` so the response
     *   includes a `nextSyncToken` for delta-sync.  Subsequent calls pass that
     *   token via [syncToken] (and omit `requestSyncToken`) to get only the
     *   contacts that have changed.
     *
     * Spec: https://developers.google.com/people/api/rest/v1/people.connections/list
     */
    @GET("v1/people/me/connections")
    suspend fun listConnections(
        @Header("Authorization") auth: String,
        @Query("personFields") personFields: String = "names,emailAddresses",
        @Query("pageSize") pageSize: Int = 200,
        @Query("pageToken") pageToken: String? = null,
        @Query("syncToken") syncToken: String? = null,
        @Query("requestSyncToken") requestSyncToken: Boolean? = null
    ): PeopleConnectionsResponse
}
