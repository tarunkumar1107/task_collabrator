package com.taskmaster.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenDenylist {

    private final Map<String, Instant> denylisted = new ConcurrentHashMap<>();

    public void revoke(String jti, Instant expiresAt) {
        cleanup();
        denylisted.put(jti, expiresAt);
    }

    public boolean isRevoked(String jti) {
        cleanup();
        Instant expiresAt = denylisted.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt.isBefore(Instant.now())) {
            denylisted.remove(jti);
            return false;
        }
        return true;
    }

    private void cleanup() {
        Instant now = Instant.now();
        denylisted.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }
}
