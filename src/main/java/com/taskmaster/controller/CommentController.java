package com.taskmaster.controller;

import com.taskmaster.dto.CollaborationDtos.CommentResponse;
import com.taskmaster.dto.CollaborationDtos.CreateCommentRequest;
import com.taskmaster.security.SecurityUtils;
import com.taskmaster.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks/{taskId}/comments")
@Tag(name = "Comments", description = "Collaborate on tasks with threaded comments")
@SecurityRequirement(name = "bearerAuth")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    @Operation(summary = "List comments", description = "Returns comments for a task in chronological order.")
    public List<CommentResponse> list(@PathVariable Long taskId) {
        return commentService.listComments(SecurityUtils.currentUser(), taskId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add comment", description = "Adds a comment to a task as the authenticated user.")
    public CommentResponse create(
            @PathVariable Long taskId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        return commentService.addComment(SecurityUtils.currentUser(), taskId, request);
    }
}
