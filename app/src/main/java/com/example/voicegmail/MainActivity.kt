package com.example.voicegmail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.voicegmail.contacts.ContactManager

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_READ_CONTACTS = 100

    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
        setContent { /* your Compose UI */ }
        checkPermissionsAndLoadContacts()
    } catch (e: Exception) {
        Toast.makeText(this, "Startup error: ${e::class.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
        e.printStackTrace()
    }
}



    private fun checkPermissionsAndLoadContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            
            // Requesting the permission at runtime
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                PERMISSIONS_REQUEST_READ_CONTACTS
            )
        } else {
            // Permission is already granted
            loadContacts()
        }
    }

    private fun loadContacts() {
        try {
            val contactManager = ContactManager(this)
            val contactList = contactManager.getContactList()

            // Toast feedback to confirm the contact code is running correctly
            Toast.makeText(this, "Successfully loaded ${contactList.size} contacts", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error accessing contacts: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_READ_CONTACTS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadContacts()
            } else {
                Toast.makeText(this, "Contacts permission is required for this app to function.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
