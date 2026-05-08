package com.example.voicegmail.debug

import android.content.Intent
import net.openid.appauth.AuthorizationException

private val redactedExtraKeys = setOf("code", "state", "access_token", "refresh_token", "client_secret")

internal fun Intent?.toDebugString(): String =
    "intentAction=${this?.action}, intentData=${this?.data?.toString()}, extras=${this?.extras?.keySet()?.toList()?.sorted()?.map { key -> if (key in redactedExtraKeys) "$key=<redacted>" else key }}"

internal fun AuthorizationException?.toDebugString(): String =
    this?.let {
        "type=${it.type}, code=${it.code}, error=${it.error}, description=${it.errorDescription}, uri=${it.errorUri}"
    } ?: "null"
