package com.taskmaster.controller;

import com.taskmaster.dto.CollaborationDtos.AttachmentResponse;
import com.taskmaster.security.SecurityUtils;
import com.taskmaster.service.AttachmentService;
import com.taskmaster.service.AttachmentService.DownloadPayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Attachments", description = "Upload and download task file attachments")
@SecurityRequirement(name = "bearerAuth")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping("/tasks/{taskId}/attachments")
    @Operation(summary = "List attachments", description = "Returns metadata for files attached to a task.")
    public List<AttachmentResponse> list(@PathVariable Long taskId) {
        return attachmentService.listAttachments(SecurityUtils.currentUser(), taskId);
    }

    @PostMapping(value = "/tasks/{taskId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload attachment", description = "Uploads a file to a task (multipart form field: file).")
    public AttachmentResponse upload(
            @PathVariable Long taskId,
            @RequestPart("file") MultipartFile file
    ) {
        return attachmentService.upload(SecurityUtils.currentUser(), taskId, file);
    }

    @GetMapping("/attachments/{id}/download")
    @Operation(summary = "Download attachment", description = "Streams the attachment file for download.")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        DownloadPayload payload = attachmentService.download(SecurityUtils.currentUser(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + payload.fileName() + "\"")
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.resource());
    }
}
