package pl.piomin.services.deskpilot.cloud;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stored configuration of a cloud provider: enabled flag and the encrypted API key (never the plain key). */
@Entity
@Table(name = "cloud_provider")
public class CloudProvider {

    @Id
    @Column(length = 30)
    private String id;

    private boolean enabled;

    @Column(columnDefinition = "text")
    private String apiKeyEncrypted;

    /** Last characters of the key, to recognise it in the UI. */
    @Column(length = 8)
    private String keyHint;

    @Column(length = 64)
    private String updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CloudProvider() {
    }

    public CloudProvider(String id) {
        this.id = id;
        this.updatedAt = Instant.now();
    }

    void update(boolean enabled, String actor) {
        this.enabled = enabled;
        touch(actor);
    }

    void storeKey(String encrypted, String hint, String actor) {
        this.apiKeyEncrypted = encrypted;
        this.keyHint = hint;
        touch(actor);
    }

    void removeKey(String actor) {
        this.apiKeyEncrypted = null;
        this.keyHint = null;
        touch(actor);
    }

    private void touch(String actor) {
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public String getKeyHint() {
        return keyHint;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
