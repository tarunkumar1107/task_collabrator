package com.taskmaster.dto;

import com.taskmaster.domain.Attachment;
import com.taskmaster.domain.Comment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class CollaborationDtos {

    private CollaborationDtos() {
    }

    @Schema(
            name = "CreateCommentRequest",
            description = "Comment to add on a task",
            example = "{ \"body\": \"Please attach the Figma link when ready.\" }"
    )
    public record CreateCommentRequest(
            @Schema(description = "Comment text", example = "Please attach the Figma link when ready.", maxLength = 4000)
            @NotBlank(message = "Comment body is required")
            @Size(max = 4000, message = "Comment must be at most 4000 characters")
            String body
    ) {
    }

    @Schema(name = "CommentResponse", description = "Comment on a task")
    public record CommentResponse(
            @Schema(description = "Comment id", example = "1")
            Long id,

            @Schema(description = "Task id", example = "1")
            Long taskId,

            @Schema(description = "Author user id", example = "1")
            Long authorId,

            @Schema(description = "Author email", example = "alice@example.com")
            String authorEmail,

            @Schema(description = "Comment body", example = "Please attach the Figma link when ready.")
            String body,

            @Schema(description = "When the comment was created")
            Instant createdAt
    ) {
        public static CommentResponse from(Comment comment) {
            return new CommentResponse(
                    comment.getId(),
                    comment.getTask().getId(),
                    comment.getAuthor().getId(),
                    comment.getAuthor().getEmail(),
                    comment.getBody(),
                    comment.getCreatedAt()
            );
        }
    }

    @Schema(name = "AttachmentResponse", description = "File attachment metadata for a task")
    public record AttachmentResponse(
            @Schema(description = "Attachment id", example = "1")
            Long id,

            @Schema(description = "Task id", example = "1")
            Long taskId,

            @Schema(description = "Uploader user id", example = "1")
            Long uploadedById,

            @Schema(description = "Original file name", example = "design.pdf")
            String fileName,

            @Schema(description = "MIME type", example = "application/pdf")
            String contentType,

            @Schema(description = "File size in bytes", example = "204800")
            long sizeBytes,

            @Schema(description = "Upload timestamp")
            Instant createdAt
    ) {
        public static AttachmentResponse from(Attachment attachment) {
            return new AttachmentResponse(
                    attachment.getId(),
                    attachment.getTask().getId(),
                    attachment.getUploadedBy().getId(),
                    attachment.getFileName(),
                    attachment.getContentType(),
                    attachment.getSizeBytes(),
                    attachment.getCreatedAt()
            );
        }
    }
}
