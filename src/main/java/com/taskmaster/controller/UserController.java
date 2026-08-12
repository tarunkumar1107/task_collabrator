package com.taskmaster.controller;

import com.taskmaster.dto.UserDtos.UpdateProfileRequest;
import com.taskmaster.dto.UserDtos.UserResponse;
import com.taskmaster.security.SecurityUtils;
import com.taskmaster.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "View and update the authenticated user's profile")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get my profile", description = "Returns the currently authenticated user's profile.")
    public UserResponse me() {
        return userService.getProfile(SecurityUtils.currentUser());
    }

    @PutMapping("/me")
    @Operation(summary = "Update my profile", description = "Updates display name and bio for the authenticated user.")
    public UserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(SecurityUtils.currentUser(), request);
    }
}
