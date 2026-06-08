package com.example.voicegmail.contacts

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Singleton
class ContactManager @Inject constructor(
    private val context: Context // accept Context so `ContactManager(this)` compiles
) {
    // keep the suspend API for future async network; implement a simple placeholder
    suspend fun getContacts(): List<Contact> {
        return emptyList()
    }

    // Non-suspending wrapper used from MainActivity
    fun getContactList(): List<Contact> = runBlocking { getContacts() }
}

