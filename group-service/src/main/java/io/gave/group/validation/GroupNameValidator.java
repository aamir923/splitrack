package io.gave.group.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** PostgreSQL char_length counts Unicode code points, not Java UTF-16 code units. */
public class GroupNameValidator implements ConstraintValidator<ValidGroupName, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 100;
    }
}
