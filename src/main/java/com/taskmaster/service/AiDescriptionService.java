package com.taskmaster.service;

import com.taskmaster.config.LlmProperties;
import com.taskmaster.domain.Task;
import com.taskmaster.dto.AiDtos.GenerateDescriptionResponse;
import com.taskmaster.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiDescriptionService {

    private final TaskService taskService;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;

    public AiDescriptionService(TaskService taskService, LlmClient llmClient, LlmProperties llmProperties) {
        this.taskService = taskService;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
    }

    /**
     * Drafts a description for a task from its title and any existing notes. This only returns a
     * suggestion — it does not modify the stored task.
     */
    @Transactional(readOnly = true)
    public GenerateDescriptionResponse generateDescription(UserPrincipal principal, Long taskId) {
        Task task = taskService.requireAccessibleTask(principal.getId(), taskId);
        String generated = llmClient.generateTaskDescription(task.getTitle(), task.getDescription());
        String source = llmProperties.isConfigured() ? "openai" : "stub";
        return new GenerateDescriptionResponse(task.getId(), task.getTitle(), generated, source);
    }
}
