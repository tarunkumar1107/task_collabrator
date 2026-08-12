package com.taskmaster.service;

import com.taskmaster.domain.Project;
import com.taskmaster.domain.Task;
import com.taskmaster.domain.TaskStatus;
import com.taskmaster.domain.User;
import com.taskmaster.dto.TaskDtos.AssignTaskRequest;
import com.taskmaster.dto.TaskDtos.CreateTaskRequest;
import com.taskmaster.dto.TaskDtos.TaskResponse;
import com.taskmaster.dto.TaskDtos.UpdateStatusRequest;
import com.taskmaster.dto.TaskDtos.UpdateTaskRequest;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.ProjectMemberRepository;
import com.taskmaster.repository.TaskRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    public TaskService(
            TaskRepository taskRepository,
            ProjectService projectService,
            ProjectMemberRepository projectMemberRepository,
            UserRepository userRepository
    ) {
        this.taskRepository = taskRepository;
        this.projectService = projectService;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TaskResponse createTask(UserPrincipal principal, Long projectId, CreateTaskRequest request) {
        Project project = projectService.requireMembership(projectId, principal.getId());
        User creator = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        Task task = new Task();
        task.setProject(project);
        task.setCreatedBy(creator);
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setDueDate(request.dueDate());
        task.setStatus(TaskStatus.OPEN);

        if (request.assigneeId() != null) {
            task.setAssignee(requireProjectMemberUser(projectId, request.assigneeId()));
        }

        return TaskResponse.from(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listMyTasks(UserPrincipal principal, String sort) {
        return taskRepository.findByAssigneeId(principal.getId(), parseSort(sort)).stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listProjectTasks(
            UserPrincipal principal,
            Long projectId,
            TaskStatus status,
            String q,
            String sort
    ) {
        projectService.requireMembership(projectId, principal.getId());
        String query = StringUtils.hasText(q) ? q.trim() : null;
        return taskRepository.searchInProject(projectId, status, query, parseSort(sort)).stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(UserPrincipal principal, Long taskId) {
        Task task = requireAccessibleTask(principal.getId(), taskId);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse updateTask(UserPrincipal principal, Long taskId, UpdateTaskRequest request) {
        Task task = requireAccessibleTask(principal.getId(), taskId);
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setDueDate(request.dueDate());
        return TaskResponse.from(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse updateStatus(UserPrincipal principal, Long taskId, UpdateStatusRequest request) {
        Task task = requireAccessibleTask(principal.getId(), taskId);
        task.setStatus(request.status());
        return TaskResponse.from(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse assignTask(UserPrincipal principal, Long taskId, AssignTaskRequest request) {
        Task task = requireAccessibleTask(principal.getId(), taskId);
        User assignee = requireProjectMemberUser(task.getProject().getId(), request.assigneeId());
        task.setAssignee(assignee);
        return TaskResponse.from(taskRepository.save(task));
    }

    @Transactional
    public void deleteTask(UserPrincipal principal, Long taskId) {
        Task task = requireAccessibleTask(principal.getId(), taskId);
        taskRepository.delete(task);
    }

    public Task requireAccessibleTask(Long userId, Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));
        projectService.requireMembership(task.getProject().getId(), userId);
        return task;
    }

    private User requireProjectMemberUser(Long projectId, Long userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Assignee must be a member of the project");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignee not found"));
    }

    private Sort parseSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        return switch (property) {
            case "dueDate", "createdAt", "title", "status", "updatedAt" -> Sort.by(direction, property);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported sort field: " + property);
        };
    }
}
