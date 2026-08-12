package com.taskmaster.dto;

import com.taskmaster.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserResponse(
            Long id,
            String email,
            String displayName,
            String bio,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getBio(),
                    user.getCreatedAt(),
                    user.getUpdatedAt()
            );
        }
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Display name is required")
            @Size(max = 100, message = "Display name must be at most 100 characters")
            String displayName,

            @Size(max = 500, message = "Bio must be at most 500 characters")
            String bio
    ) {
    }
}
