package io.gave.user.controller;

import io.gave.user.dto.InviteRequest;
import io.gave.user.dto.InviteResponse;
import io.gave.user.dto.UserLookupRequest;
import io.gave.user.dto.UserLookupResponse;
import io.gave.user.service.UserDirectoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. Not routed by the api-gateway, so they are unreachable from outside.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final UserDirectoryService directory;

    public InternalUserController(UserDirectoryService directory) {
        this.directory = directory;
    }

    @PostMapping("/lookup")
    UserLookupResponse lookup(@Valid @RequestBody UserLookupRequest request) {
        return directory.lookup(request.ids());
    }

    @PostMapping("/invite")
    ResponseEntity<InviteResponse> invite(@Valid @RequestBody InviteRequest request) {
        UserDirectoryService.InviteResult result = directory.invite(request.email());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.invite());
    }
}
