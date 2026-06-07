package com.example.voicegmail.contacts

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the retrieval of contacts from the Google People API.
 */
@Singleton
class ContactManager @Inject constructor(
    private val peopleApiService: PeopleApiService
) {
    /**
     * Fetches contacts for the user. Currently returns an empty list 
     * as a placeholder for the voice-driven flow.
     */
    suspend fun getContacts(): List<Contact> {
        return emptyList()
    }
}
