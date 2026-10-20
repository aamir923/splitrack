package io.gave.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteRequest(

        @NotBlank(message = "is required")
        @Email(message = "must be a valid email address")
        @Size(max = 320, message = "must be at most 320 characters")
        String email) {
}
