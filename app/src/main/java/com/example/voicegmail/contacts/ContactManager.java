package com.example.voicegmail.contacts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import java.util.ArrayList;
import java.util.List;

public class ContactManager {
    private final Context context;

    public ContactManager(Context context) {
        this.context = context;
    }

    public List<Contact> getContactList() {
        List<Contact> contactList = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        
        // We query the system for names and email addresses
        Cursor cur = cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                null, null, null, null);

        if (cur != null) {
            try {
                int nameIdx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME);
                int emailIdx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS);
                
                while (cur.moveToNext()) {
                    if (nameIdx >= 0 && emailIdx >= 0) {
                        String name = cur.getString(nameIdx);
                        String email = cur.getString(emailIdx);
                        contactList.add(new Contact(name, email));
                    }
                }
            } finally {
                cur.close();
            }
        }
        return contactList;
    }
}
