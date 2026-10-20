package pl.piomin.services.deskpilot.notifications;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user's e-mail address, from the access token, so ticket confirmations can reach them. */
@Entity
@Table(name = "user_contact")
public class UserContact {

    @Id
    @Column(length = 64)
    private String userId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserContact() {
    }

    public UserContact(String userId, String email) {
        this.userId = userId;
        this.email = email;
        this.updatedAt = Instant.now();
    }

    void update(String newEmail) {
        this.email = newEmail;
        this.updatedAt = Instant.now();
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }
}
