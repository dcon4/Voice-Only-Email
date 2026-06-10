package com.example.voicegmail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.voicegmail.contacts.ContactManager
import com.example.voicegmail.debug.DebugLogger
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "VoiceGmail",
                            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
                        )
                    }
                }
            }
            requestNeededPermissions()
        } catch (e: Exception) {
            Toast.makeText(this, "Startup error: ${e::class.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)

            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.READ_CONTACTS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            loadContacts()
            return
        }

        ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST_ALL)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_ALL) return

        val byPermission = permissions.zip(grantResults.toTypedArray()).toMap()

        if (byPermission[Manifest.permission.RECORD_AUDIO] == PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log("MainActivity", "RECORD_AUDIO granted")
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice commands.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (byPermission[Manifest.permission.POST_NOTIFICATIONS] == PackageManager.PERMISSION_GRANTED) {
            DebugLogger.log("MainActivity", "POST_NOTIFICATIONS granted")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                this,
                "Notification permission denied. Wake-on-power-button may be unreliable.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (byPermission[Manifest.permission.READ_CONTACTS] == PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        } else {
            Toast.makeText(
                this,
                "Contacts permission denied. Recipients cannot be resolved from your contact list.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadContacts() {
        try {
            val contactManager = ContactManager(this)
            val contactList = contactManager.getContactList()
            Toast.makeText(this, "Successfully loaded ${contactList.size} contacts", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error accessing contacts: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_ALL = 100
    }
}
