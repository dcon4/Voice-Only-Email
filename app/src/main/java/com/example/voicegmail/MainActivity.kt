package com.example.voicegmail;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.voicegmail.contacts.Contact;
import com.example.voicegmail.contacts.ContactManager;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int CONTACT_PERMISSION_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check for permission as soon as the app opens
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, CONTACT_PERMISSION_CODE);
        } else {
            readContactsTask();
        }
    }

    private void readContactsTask() {
        try {
            // This line is what finally "calls" the code in your contacts folder
            ContactManager contactManager = new ContactManager(this);
            List<Contact> contacts = contactManager.getContactList();
            
            String result = "Success: Found " + contacts.size() + " contacts.";
            Log.d("VOICE_GMAIL", result);
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("VOICE_GMAIL", "Error reading contacts: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CONTACT_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                readContactsTask();
            } else {
                Toast.makeText(this, "Permission denied. The app needs contacts to work.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
