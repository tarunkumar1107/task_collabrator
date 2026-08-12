package com.taskmaster.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI taskMasterOpenAPI(@Value("${server.port:8080}") int serverPort) {
        return new OpenAPI()
                .info(new Info()
                        .title("TaskMaster API")
                        .description("""
                                REST API for collaborative task tracking.

                                **Authentication:** call `POST /api/auth/register` or `POST /api/auth/login`, \
                                then use **Authorize** with `Bearer <accessToken>` for protected routes.

                                **Features:** users, projects, task CRUD, assignment, filter/search, comments, attachments.
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("TaskMaster").email("support@taskmaster.local"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("http://localhost:" + serverPort).description("Local")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token from register or login")));
    }
}
