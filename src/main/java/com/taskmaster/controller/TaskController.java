package com.taskmaster.controller;

import com.taskmaster.domain.TaskStatus;
import com.taskmaster.dto.TaskDtos.AssignTaskRequest;
import com.taskmaster.dto.TaskDtos.CreateTaskRequest;
import com.taskmaster.dto.TaskDtos.TaskResponse;
import com.taskmaster.dto.TaskDtos.UpdateStatusRequest;
import com.taskmaster.dto.TaskDtos.UpdateTaskRequest;
import com.taskmaster.security.SecurityUtils;
import com.taskmaster.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Tasks", description = "Create, assign, filter, search, and manage tasks")
@SecurityRequirement(name = "bearerAuth")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create task",
            description = """
                    Creates a task in a project. The `description` field supports detailed notes \
                    (up to 5000 characters). Status defaults to OPEN. Optional assignee must be a project member.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Task created",
                    content = @Content(schema = @Schema(implementation = TaskResponse.class))),
            @ApiResponse(responseCode = "400", ref = "#/components/responses/ValidationError"),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public TaskResponse create(
            @Parameter(description = "Project id", example = "1") @PathVariable Long projectId,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        return taskService.createTask(SecurityUtils.currentUser(), projectId, request);
    }

    @GetMapping("/projects/{projectId}/tasks")
    @Operation(
            summary = "List project tasks",
            description = """
                    Returns tasks in a project. Filter by `status`, search `q` against title/description, \
                    and sort with `sort` (e.g. `dueDate,asc`, `createdAt,desc`).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching tasks",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaskResponse.class)))),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
    })
    public List<TaskResponse> listInProject(
            @Parameter(description = "Project id", example = "1") @PathVariable Long projectId,
            @Parameter(description = "Filter by task status") @RequestParam(required = false) TaskStatus status,
            @Parameter(description = "Search title or description") @RequestParam(required = false) String q,
            @Parameter(description = "Sort field and direction, e.g. dueDate,asc or createdAt,desc")
            @RequestParam(required = false) String sort
    ) {
        return taskService.listProjectTasks(SecurityUtils.currentUser(), projectId, status, q, sort);
    }

    @GetMapping("/tasks/mine")
    @Operation(summary = "List my assigned tasks", description = "Returns tasks assigned to the authenticated user.")
    public List<TaskResponse> listMine(
            @Parameter(description = "Sort field and direction, e.g. dueDate,asc")
            @RequestParam(required = false) String sort
    ) {
        return taskService.listMyTasks(SecurityUtils.currentUser(), sort);
    }

    @GetMapping("/tasks/{id}")
    @Operation(summary = "Get task", description = "Returns a task if the caller is a member of its project.")
    public TaskResponse get(@PathVariable Long id) {
        return taskService.getTask(SecurityUtils.currentUser(), id);
    }

    @PutMapping("/tasks/{id}")
    @Operation(summary = "Update task", description = "Updates title, description, and due date.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task updated",
                    content = @Content(schema = @Schema(implementation = TaskResponse.class))),
            @ApiResponse(responseCode = "400", ref = "#/components/responses/ValidationError"),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    public TaskResponse update(
            @Parameter(description = "Task id", example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateTaskRequest request
    ) {
        return taskService.updateTask(SecurityUtils.currentUser(), id, request);
    }

    @PatchMapping("/tasks/{id}/status")
    @Operation(summary = "Update task status", description = "Sets status to OPEN, IN_PROGRESS, or COMPLETED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated",
                    content = @Content(schema = @Schema(implementation = TaskResponse.class))),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
            @ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    public TaskResponse updateStatus(
            @Parameter(description = "Task id", example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        return taskService.updateStatus(SecurityUtils.currentUser(), id, request);
    }

    @PatchMapping("/tasks/{id}/assignee")
    @Operation(summary = "Assign task", description = "Assigns the task to a project member.")
    public TaskResponse assign(@PathVariable Long id, @Valid @RequestBody AssignTaskRequest request) {
        return taskService.assignTask(SecurityUtils.currentUser(), id, request);
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete task", description = "Permanently deletes a task.")
    public void delete(@PathVariable Long id) {
        taskService.deleteTask(SecurityUtils.currentUser(), id);
    }
}
