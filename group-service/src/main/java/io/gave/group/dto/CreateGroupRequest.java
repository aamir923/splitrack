package io.gave.group.dto;

import io.gave.group.validation.ValidGroupName;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateGroupRequest(
        @ValidGroupName @JsonDeserialize(using = GroupNameDeserializer.class) String name) {
    public CreateGroupRequest {
        if (name != null) name = name.strip();
    }
}
