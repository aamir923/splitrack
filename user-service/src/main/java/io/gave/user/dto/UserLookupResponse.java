package io.gave.user.dto;

import java.util.List;
import java.util.UUID;

public record UserLookupResponse(List<UserSummary> users, List<UUID> missing) {
}
