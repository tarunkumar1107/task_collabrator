package com.taskmaster.service;

import com.taskmaster.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiLlmClientTest {

    @Test
    void generateTaskDescription_returnsLocalStubWhenApiKeyIsPlaceholder() {
        LlmProperties llmProperties = new LlmProperties(
                LlmProperties.DUMMY_API_KEY, "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", 15000
        );
        OpenAiLlmClient client = new OpenAiLlmClient(llmProperties, RestClient.builder());

        String description = client.generateTaskDescription("Draft wireframes", "Mobile + desktop");

        assertThat(description)
                .contains("Draft wireframes")
                .contains("Mobile + desktop");
    }

    @Test
    void generateTaskDescription_stubHandlesMissingNotes() {
        LlmProperties llmProperties = new LlmProperties(
                "", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", 15000
        );
        OpenAiLlmClient client = new OpenAiLlmClient(llmProperties, RestClient.builder());

        String description = client.generateTaskDescription("Draft wireframes", null);

        assertThat(description).contains("Draft wireframes");
    }
}
