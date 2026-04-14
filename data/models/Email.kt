package com.example.voiceonlyemail.data.models

data class Email(
    val id: String,
    val subject: String,
    val body: String,
    val sender: String,
    val timestamp: Long
)

data class Contact(
    val name: String,
    val email: String
)