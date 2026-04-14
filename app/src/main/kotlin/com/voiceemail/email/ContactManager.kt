package com.voiceemail.email

class ContactManager {
    private val contacts = mutableListOf<String>()

    fun addContact(contact: String) {
        contacts.add(contact)
    }

    fun lookupContact(name: String): String? {
        return contacts.find { it.contains(name, ignoreCase = true) }
    }

    fun suggestContacts(prefix: String): List<String> {
        return contacts.filter { it.startsWith(prefix, ignoreCase = true) }
    }
}