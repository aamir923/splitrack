package io.gave.user.service;

import io.gave.user.domain.AuthProvider;

public record ProviderProfile(
    AuthProvider provider,
    String subject,
    String email,
    boolean emailVerified,
    String name,
    String pictureUrl,
    String locale) {

}
