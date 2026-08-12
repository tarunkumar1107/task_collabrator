package com.taskmaster.security;

import com.taskmaster.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;
    private TokenDenylist tokenDenylist;

    @BeforeEach
    void setUp() {
        tokenDenylist = new TokenDenylist();
        JwtProperties properties = new JwtProperties(
                "TaskMasterDevSecretKeyThatIsLongEnoughForHS256AlgorithmSecurity",
                3_600_000L
        );
        jwtService = new JwtService(properties, tokenDenylist);
    }

    @Test
    void generateAndValidateToken() {
        UserPrincipal principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        String token = jwtService.generateToken(principal);

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractEmail(token)).isEqualTo("alice@example.com");
    }

    @Test
    void revokeToken_makesTokenInvalid() {
        UserPrincipal principal = new UserPrincipal(1L, "alice@example.com", "hash", "Alice");
        String token = jwtService.generateToken(principal);

        jwtService.revokeToken(token);

        assertThat(jwtService.isTokenValid(token)).isFalse();
    }
}
