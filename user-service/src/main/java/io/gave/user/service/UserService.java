package io.gave.user.service;

import io.gave.user.domain.User;
import io.gave.user.dto.ProfileResponse;
import io.gave.user.dto.UpdateProfileRequest;
import io.gave.user.exception.AccountDeletedException;
import io.gave.user.exception.InvalidProfileException;
import io.gave.user.exception.UserNotFoundException;
import io.gave.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        return ProfileResponse.from(loadActiveUser(userId));
    }

    @Transactional
    public ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = loadActiveUser(userId);
        if (request.defaultCurrency() != null) {
            requireKnownCurrency(request.defaultCurrency());
        }
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        user.updateProfile(displayName, request.defaultCurrency(), request.locale());
        return ProfileResponse.from(user);
    }

    private User loadActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.isDeleted()) {
            throw new AccountDeletedException(userId);
        }
        return user;
    }

    private static void requireKnownCurrency(String code) {
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidProfileException("Unknown currency code: " + code);
        }
    }
}
