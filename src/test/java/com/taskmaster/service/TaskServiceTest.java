package com.taskmaster.service;

import com.taskmaster.domain.Project;
import com.taskmaster.domain.Task;
import com.taskmaster.domain.TaskStatus;
import com.taskmaster.domain.User;
import com.taskmaster.dto.TaskDtos.AssignTaskRequest;
import com.taskmaster.dto.TaskDtos.CreateTaskRequest;
import com.taskmaster.dto.TaskDtos.UpdateStatusRequest;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.ProjectMemberRepository;
import com.taskmaster.repository.TaskRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TaskService taskService;

    private UserPrincipal principal;
    private User creator;
    private Project project;
    private Task task;

    @BeforeEach
    void setUp() {
        principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        creator = new User();
        creator.setId(1L);
        creator.setEmail("alice@example.com");

        User owner = new User();
        owner.setId(1L);
        project = new Project();
        project.setId(10L);
        project.setOwner(owner);
        project.setName("Demo");

        task = new Task();
        task.setId(100L);
        task.setProject(project);
        task.setCreatedBy(creator);
        task.setTitle("Write docs");
        task.setStatus(TaskStatus.OPEN);
    }

    @Test
    void createTask_persistsOpenTask() {
        CreateTaskRequest request = new CreateTaskRequest("Write docs", "LLD", LocalDate.now().plusDays(2), null);
        when(projectService.requireMembership(10L, 1L)).thenReturn(project);
        when(userRepository.findById(1L)).thenReturn(Optional.of(creator));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        var response = taskService.createTask(principal, 10L, request);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(response.title()).isEqualTo("Write docs");
    }

    @Test
    void listProjectTasks_appliesFilters() {
        when(projectService.requireMembership(10L, 1L)).thenReturn(project);
        when(taskRepository.searchInProject(eq(10L), eq(TaskStatus.OPEN), eq("docs"), any(Sort.class)))
                .thenReturn(List.of(task));

        var result = taskService.listProjectTasks(principal, 10L, TaskStatus.OPEN, "docs", "dueDate,asc");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Write docs");
    }

    @Test
    void updateStatus_marksCompleted() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectService.requireMembership(10L, 1L)).thenReturn(project);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = taskService.updateStatus(principal, 100L, new UpdateStatusRequest(TaskStatus.COMPLETED));

        assertThat(response.status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void assignTask_rejectsNonMember() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectService.requireMembership(10L, 1L)).thenReturn(project);
        when(projectMemberRepository.existsByProjectIdAndUserId(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> taskService.assignTask(principal, 100L, new AssignTaskRequest(99L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteTask_removesEntity() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(projectService.requireMembership(10L, 1L)).thenReturn(project);

        taskService.deleteTask(principal, 100L);

        verify(taskRepository).delete(task);
    }
}
