            try {
                int nameIdx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME);
                int emailIdx = cur.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS);

                if (nameIdx >= 0 && emailIdx >= 0) {
                    while (cur.moveToNext()) {
                        String name = cur.getString(nameIdx);
                        String email = cur.getString(emailIdx);
                        
                        if (email != null && !email.isEmpty()) {
                            contactList.add(new Contact(name, email, Contact.Source.Device));
                        }
                    }
                }
            } finally {
