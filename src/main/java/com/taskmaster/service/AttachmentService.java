package com.taskmaster.service;

import com.taskmaster.config.AppProperties;
import com.taskmaster.domain.Attachment;
import com.taskmaster.domain.Task;
import com.taskmaster.domain.User;
import com.taskmaster.dto.CollaborationDtos.AttachmentResponse;
import com.taskmaster.exception.ApiException;
import com.taskmaster.repository.AttachmentRepository;
import com.taskmaster.repository.UserRepository;
import com.taskmaster.security.UserPrincipal;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final TaskService taskService;
    private final UserRepository userRepository;
    private final Path uploadRoot;

    public AttachmentService(
            AttachmentRepository attachmentRepository,
            TaskService taskService,
            UserRepository userRepository,
            AppProperties appProperties
    ) {
        this.attachmentRepository = attachmentRepository;
        this.taskService = taskService;
        this.userRepository = userRepository;
        this.uploadRoot = Paths.get(appProperties.uploadDir()).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> listAttachments(UserPrincipal principal, Long taskId) {
        taskService.requireAccessibleTask(principal.getId(), taskId);
        return attachmentRepository.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @Transactional
    public AttachmentResponse upload(UserPrincipal principal, Long taskId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File is required");
        }

        Task task = taskService.requireAccessibleTask(principal.getId(), taskId);
        User uploader = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        try {
            Files.createDirectories(uploadRoot);
            String storedName = UUID.randomUUID() + "_" + sanitizeFileName(file.getOriginalFilename());
            Path destination = uploadRoot.resolve(storedName);
            file.transferTo(destination);

            Attachment attachment = new Attachment();
            attachment.setTask(task);
            attachment.setUploadedBy(uploader);
            attachment.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : storedName);
            attachment.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
            attachment.setStoragePath(destination.toString());
            attachment.setSizeBytes(file.getSize());
            return AttachmentResponse.from(attachmentRepository.save(attachment));
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store attachment");
        }
    }

    @Transactional(readOnly = true)
    public DownloadPayload download(UserPrincipal principal, Long attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found"));
        taskService.requireAccessibleTask(principal.getId(), attachment.getTask().getId());

        Path path = Paths.get(attachment.getStoragePath());
        if (!Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attachment file missing on disk");
        }

        Resource resource = new FileSystemResource(path);
        return new DownloadPayload(resource, attachment.getFileName(), attachment.getContentType());
    }

    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "file";
        }
        return original.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record DownloadPayload(Resource resource, String fileName, String contentType) {
    }
}
