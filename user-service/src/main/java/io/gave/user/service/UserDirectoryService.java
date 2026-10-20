package io.gave.user.service;

import io.gave.user.domain.User;
import io.gave.user.domain.UserStatus;
import io.gave.user.dto.InviteResponse;
import io.gave.user.dto.UserLookupResponse;
import io.gave.user.dto.UserSummary;
import io.gave.user.exception.ConflictException;
import io.gave.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lookups other services need (member names, invitations). Used by the internal endpoints only.
 */
@Service
public class UserDirectoryService {

    private static final String DEFAULT_CURRENCY = "INR";

    private final UserRepository userRepository;

    public UserDirectoryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record InviteResult(InviteResponse invite, boolean created) {
    }

    @Transactional(readOnly = true)
    public UserLookupResponse lookup(List<UUID> ids) {
        Set<UUID> requested = new LinkedHashSet<>(ids);
        Map<UUID, User> found = userRepository.findAllById(requested).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<UserSummary> users = requested.stream()
                .filter(found::containsKey)
                .map(id -> UserSummary.from(found.get(id)))
                .toList();
        List<UUID> missing = requested.stream()
                .filter(id -> !found.containsKey(id))
                .toList();
        return new UserLookupResponse(users, missing);
    }

    // Deliberately not @Transactional: each repository call runs in its own transaction, so a
    // unique-constraint failure from a concurrent invite does not poison the lookup that follows.
    public InviteResult invite(String rawEmail) {
        String email = rawEmail.trim().toLowerCase();

        return userRepository.findByEmail(email)
                .map(this::existing)
                .orElseGet(() -> create(email));
    }

    private InviteResult create(String email) {
        try {
            User saved = userRepository.saveAndFlush(
                    new User(email, localPartOf(email), DEFAULT_CURRENCY, UserStatus.INVITED));
            return new InviteResult(new InviteResponse(saved.getId(), saved.getStatus()), true);
        } catch (DataIntegrityViolationException e) {
            // another request created the same email between our check and our insert
            User winner = userRepository.findByEmail(email).orElseThrow(() -> e);
            return existing(winner);
        }
    }

    private InviteResult existing(User user) {
        if (user.isDeleted()) {
            throw new ConflictException("This email address cannot be invited");
        }
        return new InviteResult(new InviteResponse(user.getId(), user.getStatus()), false);
    }

    private static String localPartOf(String email) {
        return email.substring(0, email.indexOf('@'));
    }
}
