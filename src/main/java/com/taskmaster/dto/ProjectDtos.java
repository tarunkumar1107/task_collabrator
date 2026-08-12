package com.taskmaster.dto;

import com.taskmaster.domain.Project;
import com.taskmaster.domain.ProjectMember;
import com.taskmaster.domain.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(
            @NotBlank(message = "Project name is required")
            @Size(max = 150, message = "Project name must be at most 150 characters")
            String name,

            @Size(max = 2000, message = "Description must be at most 2000 characters")
            String description
    ) {
    }

    public record InviteMemberRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            String email
    ) {
    }

    public record ProjectResponse(
            Long id,
            String name,
            String description,
            Long ownerId,
            String ownerEmail,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static ProjectResponse from(Project project) {
            return new ProjectResponse(
                    project.getId(),
                    project.getName(),
                    project.getDescription(),
                    project.getOwner().getId(),
                    project.getOwner().getEmail(),
                    project.getCreatedAt(),
                    project.getUpdatedAt()
            );
        }
    }

    public record MemberResponse(
            Long id,
            Long userId,
            String email,
            String displayName,
            ProjectRole role,
            Instant joinedAt
    ) {
        public static MemberResponse from(ProjectMember member) {
            return new MemberResponse(
                    member.getId(),
                    member.getUser().getId(),
                    member.getUser().getEmail(),
                    member.getUser().getDisplayName(),
                    member.getRole(),
                    member.getJoinedAt()
            );
        }
    }
}
