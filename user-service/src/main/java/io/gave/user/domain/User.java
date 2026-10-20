package io.gave.user.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "avatar_url", length = 2048)
    private String avatarUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(length = 10)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserIdentity> identities = new ArrayList<>();

    protected User() {
    }

    public User(String email, String displayName, String defaultCurrency, UserStatus status) {
        this.email = email.toLowerCase();
        this.displayName = displayName;
        this.defaultCurrency = defaultCurrency;
        this.status = status;
    }

    public void addIdentity(UserIdentity identity) {
        identities.add(identity);
        identity.setUser(this);
    }

    public boolean isDeleted() {
        return status == UserStatus.DELETED;
    }

    public void updateProfile(String displayName, String defaultCurrency, String locale) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (defaultCurrency != null) {
            this.defaultCurrency = defaultCurrency;
        }
        if (locale != null) {
            this.locale = locale;
        }
    }

    public void activate(String displayName, String avatarUrl) {
        this.status = UserStatus.ACTIVE;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public String getLocale() { return locale; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public List<UserIdentity> getIdentities() { return identities; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public void setLocale(String locale) { this.locale = locale; }
    public void setStatus(UserStatus status) { this.status = status; }
}