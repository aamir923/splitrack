package io.gave.group.dto;

import io.gave.group.validation.ValidGroupName;
import tools.jackson.databind.annotation.JsonDeserialize;

public record RenameGroupRequest(
        @ValidGroupName @JsonDeserialize(using = GroupNameDeserializer.class) String name) {
    public RenameGroupRequest {
        if (name != null) name = name.strip();
    }
}
