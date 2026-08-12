package com.taskmaster.dto;

import com.taskmaster.domain.Task;
import com.taskmaster.domain.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class TaskDtos {

    private TaskDtos() {
    }

    @Schema(
            name = "CreateTaskRequest",
            description = "Payload to create a new task inside a project. Status defaults to OPEN.",
            example = """
                    {
                      "title": "Draft wireframes",
                      "description": "Create mobile and desktop wireframes for the homepage redesign",
                      "dueDate": "2026-08-20",
                      "assigneeId": 2
                    }
                    """
    )
    public record CreateTaskRequest(
            @Schema(description = "Short task title shown in lists", example = "Draft wireframes", maxLength = 200)
            @NotBlank(message = "Title is required")
            @Size(max = 200, message = "Title must be at most 200 characters")
            String title,

            @Schema(
                    description = "Detailed task description with acceptance criteria or notes",
                    example = "Create mobile and desktop wireframes for the homepage redesign",
                    maxLength = 5000
            )
            @Size(max = 5000, message = "Description must be at most 5000 characters")
            String description,

            @Schema(description = "Optional due date (ISO-8601 date)", example = "2026-08-20")
            LocalDate dueDate,

            @Schema(description = "Optional user id to assign; must be a member of the project", example = "2")
            Long assigneeId
    ) {
    }

    @Schema(
            name = "UpdateTaskRequest",
            description = "Payload to update task title, description, and due date",
            example = """
                    {
                      "title": "Draft wireframes v2",
                      "description": "Updated scope: include tablet breakpoints",
                      "dueDate": "2026-08-22"
                    }
                    """
    )
    public record UpdateTaskRequest(
            @Schema(description = "Updated task title", example = "Draft wireframes v2", maxLength = 200)
            @NotBlank(message = "Title is required")
            @Size(max = 200, message = "Title must be at most 200 characters")
            String title,

            @Schema(
                    description = "Updated task description",
                    example = "Updated scope: include tablet breakpoints",
                    maxLength = 5000
            )
            @Size(max = 5000, message = "Description must be at most 5000 characters")
            String description,

            @Schema(description = "Updated due date (ISO-8601 date)", example = "2026-08-22")
            LocalDate dueDate
    ) {
    }

    @Schema(
            name = "UpdateStatusRequest",
            description = "Payload to change task status",
            example = "{ \"status\": \"COMPLETED\" }"
    )
    public record UpdateStatusRequest(
            @Schema(description = "New task status", example = "COMPLETED")
            @NotNull(message = "Status is required")
            TaskStatus status
    ) {
    }

    @Schema(
            name = "AssignTaskRequest",
            description = "Payload to assign a task to a project member",
            example = "{ \"assigneeId\": 2 }"
    )
    public record AssignTaskRequest(
            @Schema(description = "User id of the assignee (must be a project member)", example = "2")
            @NotNull(message = "Assignee id is required")
            Long assigneeId
    ) {
    }

    @Schema(
            name = "TaskResponse",
            description = "Task details returned by task APIs",
            example = """
                    {
                      "id": 1,
                      "projectId": 1,
                      "title": "Draft wireframes",
                      "description": "Create mobile and desktop wireframes",
                      "dueDate": "2026-08-20",
                      "status": "OPEN",
                      "assigneeId": 2,
                      "assigneeEmail": "bob@example.com",
                      "createdById": 1,
                      "createdAt": "2026-08-11T06:45:42.537912Z",
                      "updatedAt": "2026-08-11T06:45:42.537912Z"
                    }
                    """
    )
    public record TaskResponse(
            @Schema(description = "Task id", example = "1")
            Long id,

            @Schema(description = "Project the task belongs to", example = "1")
            Long projectId,

            @Schema(description = "Task title", example = "Draft wireframes")
            String title,

            @Schema(description = "Task description", example = "Create mobile and desktop wireframes")
            String description,

            @Schema(description = "Due date", example = "2026-08-20")
            LocalDate dueDate,

            @Schema(description = "Current status")
            TaskStatus status,

            @Schema(description = "Assigned user id", example = "2", nullable = true)
            Long assigneeId,

            @Schema(description = "Assigned user email", example = "bob@example.com", nullable = true)
            String assigneeEmail,

            @Schema(description = "User id who created the task", example = "1")
            Long createdById,

            @Schema(description = "Creation timestamp", example = "2026-08-11T06:45:42.537912Z")
            Instant createdAt,

            @Schema(description = "Last update timestamp", example = "2026-08-11T06:45:42.537912Z")
            Instant updatedAt
    ) {
        public static TaskResponse from(Task task) {
            return new TaskResponse(
                    task.getId(),
                    task.getProject().getId(),
                    task.getTitle(),
                    task.getDescription(),
                    task.getDueDate(),
                    task.getStatus(),
                    task.getAssignee() != null ? task.getAssignee().getId() : null,
                    task.getAssignee() != null ? task.getAssignee().getEmail() : null,
                    task.getCreatedBy().getId(),
                    task.getCreatedAt(),
                    task.getUpdatedAt()
            );
        }
    }
}
