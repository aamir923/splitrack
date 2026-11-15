package io.gave.group.controller;

import io.gave.group.dto.*;
import io.gave.group.security.CurrentUser;
import io.gave.group.service.GroupService;
import org.hibernate.validator.constraints.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

import static java.util.UUID.fromString;
import static org.hibernate.validator.constraints.UUID.LetterCase.INSENSITIVE;

@RestController
@RequestMapping("/api/groups")
public class GroupController {
    private final GroupService service;

    public GroupController(GroupService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<GroupResponse> create(Authentication authentication, @Valid @RequestBody CreateGroupRequest request) {
        GroupResponse group = service.create(caller(authentication), credential(authentication), request.name());
        return ResponseEntity.created(URI.create("/api/groups/" + group.id())).body(group);
    }

    @GetMapping
    public List<GroupResponse> list(Authentication authentication) {
        return service.list(caller(authentication), credential(authentication));
    }

    @GetMapping("/{groupId}")
    public GroupResponse view(Authentication authentication,
                              @PathVariable @UUID(version = {1, 2, 3, 4, 5, 6, 7, 8}, letterCase = INSENSITIVE) String groupId) {
        return service.view(caller(authentication), credential(authentication), fromString(groupId));
    }

    @PatchMapping("/{groupId}")
    public GroupResponse rename(Authentication authentication,
                                @PathVariable @UUID(version = {1, 2, 3, 4, 5, 6, 7, 8}, letterCase = INSENSITIVE) String groupId,
                                @Valid @RequestBody RenameGroupRequest request) {
        return service.rename(caller(authentication), credential(authentication), fromString(groupId), request.name());
    }

    @GetMapping("/{groupId}/members")
    public List<MemberResponse> members(Authentication authentication,
                                        @PathVariable @UUID(version = {1, 2, 3, 4, 5, 6, 7, 8}, letterCase = INSENSITIVE) String groupId) {
        return service.members(caller(authentication), credential(authentication), fromString(groupId));
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<MemberResponse> addMember(Authentication authentication,
                                                   @PathVariable @UUID(version = {1, 2, 3, 4, 5, 6, 7, 8}, letterCase = INSENSITIVE) String groupId,
                                                   @Valid @RequestBody AddMemberRequest request) {
        MemberResponse member = service.addMember(caller(authentication), credential(authentication),
                fromString(groupId), fromString(request.userId()));
        return ResponseEntity.status(201).body(member);
    }

    private static CurrentUser caller(Authentication authentication) { return (CurrentUser) authentication.getPrincipal(); }
    private static Jwt credential(Authentication authentication) { return (Jwt) authentication.getCredentials(); }

}
