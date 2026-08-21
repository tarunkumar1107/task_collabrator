package com.taskmaster.service;

import com.taskmaster.config.LlmProperties;
import com.taskmaster.domain.Project;
import com.taskmaster.domain.Task;
import com.taskmaster.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDescriptionServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private LlmClient llmClient;

    private UserPrincipal principal;
    private Task task;

    @BeforeEach
    void setUp() {
        principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");

        Project project = new Project();
        project.setId(10L);

        task = new Task();
        task.setId(100L);
        task.setProject(project);
        task.setTitle("Draft wireframes");
        task.setDescription("Mobile + desktop");
    }

    @Test
    void generateDescription_usesStubSourceWhenKeyNotConfigured() {
        LlmProperties llmProperties = new LlmProperties(LlmProperties.DUMMY_API_KEY, "http://ignored", "gpt-4o-mini", 15000);
        AiDescriptionService service = new AiDescriptionService(taskService, llmClient, llmProperties);

        when(taskService.requireAccessibleTask(1L, 100L)).thenReturn(task);
        when(llmClient.generateTaskDescription("Draft wireframes", "Mobile + desktop"))
                .thenReturn("Generated draft");

        var response = service.generateDescription(principal, 100L);

        assertThat(response.taskId()).isEqualTo(100L);
        assertThat(response.title()).isEqualTo("Draft wireframes");
        assertThat(response.generatedDescription()).isEqualTo("Generated draft");
        assertThat(response.source()).isEqualTo("stub");
        verify(taskService).requireAccessibleTask(eq(1L), eq(100L));
    }

    @Test
    void generateDescription_usesOpenAiSourceWhenKeyConfigured() {
        LlmProperties llmProperties = new LlmProperties("sk-real-key", "http://ignored", "gpt-4o-mini", 15000);
        AiDescriptionService service = new AiDescriptionService(taskService, llmClient, llmProperties);

        when(taskService.requireAccessibleTask(1L, 100L)).thenReturn(task);
        when(llmClient.generateTaskDescription("Draft wireframes", "Mobile + desktop"))
                .thenReturn("Generated draft");

        var response = service.generateDescription(principal, 100L);

        assertThat(response.source()).isEqualTo("openai");
    }
}
