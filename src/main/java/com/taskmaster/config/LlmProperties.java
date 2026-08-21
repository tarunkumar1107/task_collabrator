package com.taskmaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Configuration for the AI task-description feature.
 * <p>
 * {@code apiKey} defaults to a placeholder value so the app runs out of the box. While it stays
 * at that placeholder (or is left blank), {@link com.taskmaster.service.OpenAiLlmClient} skips the
 * real OpenAI call and returns a locally templated description instead — swap in a real key via
 * {@code APP_LLM_API_KEY} once one is available.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(String apiKey, String apiUrl, String model, int timeoutMs) {

    public static final String DUMMY_API_KEY = "dummy-llm-api-key";

    /**
     * True once a real (non-placeholder) API key has been configured.
     */
    public boolean isConfigured() {
        return StringUtils.hasText(apiKey) && !DUMMY_API_KEY.equalsIgnoreCase(apiKey.trim());
    }
}
