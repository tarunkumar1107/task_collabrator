package com.taskmaster.security;

import com.taskmaster.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final TokenDenylist tokenDenylist;
    private final SecretKey secretKey;

    public JwtService(JwtProperties jwtProperties, TokenDenylist tokenDenylist) {
        this.jwtProperties = jwtProperties;
        this.tokenDenylist = tokenDenylist;
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.expirationMs());
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(principal.getUsername())
                .claim("uid", principal.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseClaims(token);
            String jti = claims.getId();
            return jti != null && !tokenDenylist.isRevoked(jti) && claims.getExpiration().after(new Date());
        } catch (Exception ex) {
            return false;
        }
    }

    public void revokeToken(String token) {
        Claims claims = parseClaims(token);
        tokenDenylist.revoke(claims.getId(), claims.getExpiration().toInstant());
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }
}
