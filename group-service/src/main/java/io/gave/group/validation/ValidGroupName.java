package io.gave.group.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = GroupNameValidator.class)
public @interface ValidGroupName {
    String message() default "Group name must contain between 1 and 100 characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
