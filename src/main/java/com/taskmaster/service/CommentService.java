package com.taskmaster.service;

import com.taskmaster.domain.Comment;
import com.taskmaster.domain.Task;
import com.taskmaster.domain.User;
import com.taskmaster.dto.CollaborationDtos.CommentResponse;
import com.taskmaster.dto.CollaborationDtos.CreateCommentRequest;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.CommentRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final TaskService taskService;
    private final UserRepository userRepository;

    public CommentService(
            CommentRepository commentRepository,
            TaskService taskService,
            UserRepository userRepository
    ) {
        this.commentRepository = commentRepository;
        this.taskService = taskService;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> listComments(UserPrincipal principal, Long taskId) {
        taskService.requireAccessibleTask(principal.getId(), taskId);
        return commentRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    @Transactional
    public CommentResponse addComment(UserPrincipal principal, Long taskId, CreateCommentRequest request) {
        Task task = taskService.requireAccessibleTask(principal.getId(), taskId);
        User author = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        Comment comment = new Comment();
        comment.setTask(task);
        comment.setAuthor(author);
        comment.setBody(request.body().trim());
        return CommentResponse.from(commentRepository.save(comment));
    }
}
