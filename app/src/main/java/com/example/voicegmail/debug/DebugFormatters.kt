package com.example.voicegmail.debug

import android.content.Intent
import net.openid.appauth.AuthorizationException

internal fun Intent?.toDebugString(): String =
    "intentAction=${this?.action}, intentData=${this?.dataString}, extras=${this?.extras?.keySet()?.toList()?.sorted()}"

internal fun AuthorizationException?.toDebugString(): String =
    this?.let {
        "type=${it.type}, code=${it.code}, error=${it.error}, description=${it.errorDescription}, uri=${it.errorUri}"
    } ?: "null"
