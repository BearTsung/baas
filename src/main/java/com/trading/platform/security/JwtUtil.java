package com.trading.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMinute;

    public JwtUtil(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinute = expirationMs;
    }
    // token 簽發後 1 小時自動過期，逾時前端需引導使用者重新登入
    public String generateToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .signWith(key)
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + expirationMinute))
                .compact();
    }

    public String getUsername(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public String getRole(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload()
                .get("role", String.class);
    }

    public boolean validate(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
