package io.gave.group.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Authorization uses id/status only; profile metadata is never returned by the group API. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserLookupResponse(List<LookupUser> users, List<String> missing) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LookupUser(String id, String displayName, String avatarUrl, String status) { }
}
