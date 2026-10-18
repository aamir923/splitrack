package io.gave.user.service;

import io.gave.user.domain.User;
import io.gave.user.domain.UserIdentity;
import io.gave.user.domain.UserStatus;
import io.gave.user.exception.AccountDeletedException;
import io.gave.user.exception.UnverifiedEmailException;
import io.gave.user.repository.UserIdentityRepository;
import io.gave.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);
    private static final String DEFAULT_CURRENCY = "INR";

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    public UserProvisioningService(UserRepository userRepository,
                                   UserIdentityRepository userIdentityRepository) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
    }

    @Transactional
    public User provision(ProviderProfile profile) {
        return userIdentityRepository
                .findByProviderAndProviderSubject(profile.provider(), profile.subject())
                .map(UserIdentity::getUser)
                .map(this::requireNotDeleted)
                .orElseGet(() -> linkOrCreate(profile));
    }

    private User linkOrCreate(ProviderProfile profile) {
        if (!profile.emailVerified()) {
            throw new UnverifiedEmailException(profile.email());
        }
        String email = profile.email().trim().toLowerCase();

        return userRepository.findByEmail(email)
                .map(existing -> linkIdentity(existing, profile))
                .orElseGet(() -> createUser(email, profile));
    }

    private User linkIdentity(User existing, ProviderProfile profile) {
        requireNotDeleted(existing);
        if (existing.getStatus() == UserStatus.INVITED) {
            existing.activate(displayNameOf(profile), profile.pictureUrl());
            log.info("Invited user {} accepted via {}", existing.getId(), profile.provider());
        }
        existing.addIdentity(new UserIdentity(profile.provider(), profile.subject()));
        return userRepository.save(existing);
    }

    private User createUser(String email, ProviderProfile profile) {
        User user = new User(email, displayNameOf(profile), DEFAULT_CURRENCY, UserStatus.ACTIVE);
        user.setAvatarUrl(profile.pictureUrl());
        user.setLocale(profile.locale());
        user.addIdentity(new UserIdentity(profile.provider(), profile.subject()));
        log.info("Creating new user via {}", profile.provider());
        return userRepository.save(user);
    }

    private User requireNotDeleted(User user) {
        if (user.isDeleted()) {
            throw new AccountDeletedException(user.getId());
        }
        return user;
    }

    private String displayNameOf(ProviderProfile profile) {
        if (profile.name() != null && !profile.name().isBlank()) {
            return profile.name();
        }
        return profile.email().substring(0, profile.email().indexOf('@'));
    }
}