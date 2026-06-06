package com.example.voicegmail;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.voicegmail.contacts.Contact;
import com.example.voicegmail.contacts.ContactManager;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CODE = 123;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check if we have permission to read contacts
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, PERMISSION_CODE);
        } else {
            loadContacts();
        }
    }

    private void loadContacts() {
        try {
            ContactManager contactManager = new ContactManager(this);
            List<Contact> contacts = contactManager.getContactList();
            Toast.makeText(this, "Loaded " + contacts.size() + " contacts", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE && grantResults.length > 0 
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        }
    }
}
