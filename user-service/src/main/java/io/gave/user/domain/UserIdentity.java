package io.gave.user.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserIdentity() {
    }

    public UserIdentity(AuthProvider provider, String providerSubject) {
        this.provider = provider;
        this.providerSubject = providerSubject;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public AuthProvider getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public Instant getCreatedAt() { return createdAt; }

    void setUser(User user) { this.user = user; }
}