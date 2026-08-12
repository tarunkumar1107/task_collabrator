package com.taskmaster.config;

import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiTaskSchemaConfig {

    @Bean
    public OpenApiCustomizer taskApiResponsesCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getResponses() == null) {
                return;
            }

            openApi.getComponents().addResponses("UnauthorizedError", new ApiResponse()
                    .description("Missing or invalid JWT")
                    .content(new Content().addMediaType("application/json",
                            new MediaType().example("""
                                    {
                                      "timestamp": "2026-08-11T06:00:00Z",
                                      "status": 401,
                                      "error": "Unauthorized",
                                      "message": "Authentication required",
                                      "path": "/api/tasks/1"
                                    }
                                    """))));

            openApi.getComponents().addResponses("ForbiddenError", new ApiResponse()
                    .description("User is not a member of the project")
                    .content(new Content().addMediaType("application/json",
                            new MediaType().example("""
                                    {
                                      "timestamp": "2026-08-11T06:00:00Z",
                                      "status": 403,
                                      "error": "Forbidden",
                                      "message": "Access denied",
                                      "path": "/api/tasks/1"
                                    }
                                    """))));

            openApi.getComponents().addResponses("ValidationError", new ApiResponse()
                    .description("Request validation failed")
                    .content(new Content().addMediaType("application/json",
                            new MediaType().example("""
                                    {
                                      "timestamp": "2026-08-11T06:00:00Z",
                                      "status": 400,
                                      "error": "Bad Request",
                                      "message": "Title is required",
                                      "path": "/api/projects/1/tasks"
                                    }
                                    """))));
        };
    }
}
