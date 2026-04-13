package com.voiceemail.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "email_entities")
data class EmailEntity (
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String,
    val recipient: String,
    val subject: String,
    val body: String,
    val timestamp: Long
)