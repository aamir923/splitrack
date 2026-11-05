package io.gave.group.dto;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UUID;

import static org.hibernate.validator.constraints.UUID.LetterCase.INSENSITIVE;

public record AddMemberRequest(
        @NotNull @UUID(version = {1, 2, 3, 4, 5, 6, 7, 8}, letterCase = INSENSITIVE) String userId) { }
