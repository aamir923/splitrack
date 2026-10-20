package io.gave.user.controller;

import io.gave.user.dto.ProfileResponse;
import io.gave.user.dto.UpdateProfileRequest;
import io.gave.user.security.CurrentUser;
import io.gave.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    ProfileResponse getMe(@AuthenticationPrincipal CurrentUser user) {
        return userService.getProfile(user.userId());
    }

    @PatchMapping
    ProfileResponse updateMe(@AuthenticationPrincipal CurrentUser user,
                             @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(user.userId(), request);
    }
}
