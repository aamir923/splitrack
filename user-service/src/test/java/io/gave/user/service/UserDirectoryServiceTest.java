package io.gave.user.service;

import io.gave.user.domain.User;
import io.gave.user.domain.UserStatus;
import io.gave.user.dto.UserLookupResponse;
import io.gave.user.exception.ConflictException;
import io.gave.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDirectoryServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDirectoryService service;

    private static User user(String email, String name, UserStatus status) {
        User user = new User(email, name, "INR", status);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void lookupReturnsSummariesAndReportsMissingIds() {
        User aamir = user("aamir@example.com", "Aamir", UserStatus.ACTIVE);
        UUID unknown = UUID.randomUUID();
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(aamir));

        UserLookupResponse response = service.lookup(List.of(aamir.getId(), unknown));

        assertThat(response.users()).hasSize(1);
        assertThat(response.users().get(0).displayName()).isEqualTo("Aamir");
        assertThat(response.users().get(0).status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.missing()).containsExactly(unknown);
    }

    @Test
    void lookupIgnoresDuplicateIdsAndKeepsRequestOrder() {
        User first = user("a@example.com", "A", UserStatus.ACTIVE);
        User second = user("b@example.com", "B", UserStatus.ACTIVE);
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(second, first));

        UserLookupResponse response = service.lookup(
                List.of(first.getId(), second.getId(), first.getId()));

        assertThat(response.users()).extracting("id").containsExactly(first.getId(), second.getId());
        assertThat(response.missing()).isEmpty();
    }

    @Test
    void lookupAnonymizesDeletedUsers() {
        User deleted = user("gone@example.com", "Real Name", UserStatus.DELETED);
        ReflectionTestUtils.setField(deleted, "avatarUrl", "https://example.com/me.png");
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(deleted));

        UserLookupResponse response = service.lookup(List.of(deleted.getId()));

        assertThat(response.users().get(0).displayName()).isEqualTo("Deleted user");
        assertThat(response.users().get(0).avatarUrl()).isNull();
        assertThat(response.users().get(0).status()).isEqualTo(UserStatus.DELETED);
    }

    @Test
    void inviteCreatesAnInvitedUserWithNormalizedEmail() {
        when(userRepository.findByEmail("friend@example.com")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        UserDirectoryService.InviteResult result = service.invite("  Friend@Example.COM ");

        assertThat(result.created()).isTrue();
        assertThat(result.invite().status()).isEqualTo(UserStatus.INVITED);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("friend@example.com");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("friend");
    }

    @Test
    void inviteIsIdempotentForAnExistingActiveUser() {
        User existing = user("friend@example.com", "Friend", UserStatus.ACTIVE);
        when(userRepository.findByEmail("friend@example.com")).thenReturn(Optional.of(existing));

        UserDirectoryService.InviteResult result = service.invite("friend@example.com");

        assertThat(result.created()).isFalse();
        assertThat(result.invite().id()).isEqualTo(existing.getId());
        assertThat(result.invite().status()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void inviteIsIdempotentForAnExistingInvitedUser() {
        User existing = user("friend@example.com", "friend", UserStatus.INVITED);
        when(userRepository.findByEmail("friend@example.com")).thenReturn(Optional.of(existing));

        UserDirectoryService.InviteResult result = service.invite("friend@example.com");

        assertThat(result.created()).isFalse();
        assertThat(result.invite().status()).isEqualTo(UserStatus.INVITED);
    }

    @Test
    void inviteRejectsAnEmailBelongingToADeletedUser() {
        User deleted = user("friend@example.com", "Friend", UserStatus.DELETED);
        when(userRepository.findByEmail("friend@example.com")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.invite("friend@example.com"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void inviteRecoversWhenAConcurrentRequestCreatedTheSameEmail() {
        User winner = user("friend@example.com", "friend", UserStatus.INVITED);
        when(userRepository.findByEmail("friend@example.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        UserDirectoryService.InviteResult result = service.invite("friend@example.com");

        assertThat(result.created()).isFalse();
        assertThat(result.invite().id()).isEqualTo(winner.getId());
    }
}
