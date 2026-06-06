package com.example.voicegmail.contacts;

public class Contact {
    private final String name;
    private final String email;

    public Contact(String name, String email) {
        this.name = (name != null) ? name : "Unknown";
        this.email = (email != null) ? email : "";
    }

    public String getName() { return name; }
    public String getEmail() { return email; }
}
