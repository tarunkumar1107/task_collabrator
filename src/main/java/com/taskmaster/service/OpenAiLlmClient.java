package com.taskmaster.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.taskmaster.config.LlmProperties;
import com.taskmaster.exception.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * OpenAI Chat Completions-backed {@link LlmClient}.
 * <p>
 * While {@code app.llm.api-key} is left at its placeholder value (the out-of-the-box default),
 * this skips the network call and returns a locally templated draft instead, so the
 * generate-description endpoint works before a real key is configured. Set {@code APP_LLM_API_KEY}
 * (and optionally {@code APP_LLM_API_URL} / {@code APP_LLM_MODEL}) to call the real API.
 */
@Service
public class OpenAiLlmClient implements LlmClient {

    private static final String SYSTEM_PROMPT = """
            You write concise, actionable task descriptions for a project management tool. \
            Given a task title and any existing notes, produce a short description (2-4 sentences) \
            covering what needs to be done and, when useful, acceptance criteria. Respond with the \
            description text only — no headings, labels, or markdown.
            """;

    private final LlmProperties llmProperties;
    private final RestClient restClient;

    public OpenAiLlmClient(LlmProperties llmProperties, RestClient.Builder restClientBuilder) {
        this.llmProperties = llmProperties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(llmProperties.timeoutMs());
        requestFactory.setReadTimeout(llmProperties.timeoutMs());
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    @Override
    public String generateTaskDescription(String title, String existingNotes) {
        if (!llmProperties.isConfigured()) {
            return stubDescription(title, existingNotes);
        }

        try {
            ChatCompletionResponse response = restClient.post()
                    .uri(llmProperties.apiUrl())
                    .headers(headers -> {
                        headers.setBearerAuth(llmProperties.apiKey());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .body(new ChatCompletionRequest(
                            llmProperties.model(),
                            List.of(
                                    new ChatMessage("system", SYSTEM_PROMPT),
                                    new ChatMessage("user", buildUserPrompt(title, existingNotes))
                            ),
                            0.6
                    ))
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            String content = extractContent(response);
            if (!StringUtils.hasText(content)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI provider returned an empty description");
            }
            return content.trim();
        } catch (RestClientException ex) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to generate description from AI provider: " + ex.getMessage()
            );
        }
    }

    private String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        ChatMessage message = response.choices().get(0).message();
        return message != null ? message.content() : null;
    }

    private String buildUserPrompt(String title, String existingNotes) {
        StringBuilder prompt = new StringBuilder("Task title: ").append(title);
        if (StringUtils.hasText(existingNotes)) {
            prompt.append("\nExisting notes: ").append(existingNotes);
        }
        return prompt.toString();
    }

    /**
     * Deterministic, no-network draft used while no real API key is configured.
     */
    private String stubDescription(String title, String existingNotes) {
        String subject = StringUtils.hasText(title) ? title.trim() : "this task";
        StringBuilder description = new StringBuilder("Complete \"")
                .append(subject)
                .append("\": outline the steps needed, note any dependencies or blockers, and define what \"done\" looks like.");
        if (StringUtils.hasText(existingNotes)) {
            description.append(" Builds on existing notes: ").append(existingNotes.trim());
        }
        return description.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(ChatMessage message) {
    }
}
