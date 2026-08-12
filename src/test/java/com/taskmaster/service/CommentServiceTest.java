package com.taskmaster.service;

import com.taskmaster.domain.Comment;
import com.taskmaster.domain.Project;
import com.taskmaster.domain.Task;
import com.taskmaster.domain.User;
import com.taskmaster.dto.CollaborationDtos.CreateCommentRequest;
import com.taskmaster.repository.CommentRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private TaskService taskService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommentService commentService;

    @Test
    void addComment_persistsCommentForAccessibleTask() {
        UserPrincipal principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        User author = new User();
        author.setId(1L);
        author.setEmail("alice@example.com");

        Project project = new Project();
        project.setId(10L);
        Task task = new Task();
        task.setId(100L);
        task.setProject(project);

        when(taskService.requireAccessibleTask(1L, 100L)).thenReturn(task);
        when(userRepository.findById(1L)).thenReturn(Optional.of(author));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(7L);
            return comment;
        });

        var response = commentService.addComment(principal, 100L, new CreateCommentRequest("Looks good"));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.body()).isEqualTo("Looks good");
        assertThat(response.authorEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void listComments_returnsOrderedComments() {
        UserPrincipal principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        User author = new User();
        author.setId(1L);
        author.setEmail("alice@example.com");
        Project project = new Project();
        project.setId(10L);
        Task task = new Task();
        task.setId(100L);
        task.setProject(project);

        Comment comment = new Comment();
        comment.setId(1L);
        comment.setTask(task);
        comment.setAuthor(author);
        comment.setBody("First");

        when(taskService.requireAccessibleTask(1L, 100L)).thenReturn(task);
        when(commentRepository.findByTaskIdOrderByCreatedAtAsc(100L)).thenReturn(List.of(comment));

        var result = commentService.listComments(principal, 100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).body()).isEqualTo("First");
    }
}
