
public class Contact {
    public enum Source { Device, Gmail }

    private final String name;
    private final String email;
    private final Source source;

    public Contact(String name, String email) {
        this(name, email, Source.Device);
    }

    public Contact(String name, String email, Source source) {
        this.name = (name != null) ? name : "Unknown";
        this.email = (email != null) ? email : "";
        this.source = source;
    }

    public String getName() { return name; }
    public String getEmail() { return email; }
    public Source getSource() { return source; }
}
