package io.gave.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UserLookupRequest(

        @NotNull(message = "is required")
        @Size(min = 1, max = 100, message = "must contain between 1 and 100 ids")
        List<@NotNull(message = "must not contain null") UUID> ids) {
}
