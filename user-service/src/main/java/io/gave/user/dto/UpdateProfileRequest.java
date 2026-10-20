package io.gave.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @Size(max = 100, message = "must be at most 100 characters")
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String displayName,

        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase currency code, e.g. INR")
        String defaultCurrency,

        @Size(max = 10, message = "must be at most 10 characters")
        @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$", message = "must be a language tag such as en or en-IN")
        String locale) {
}
