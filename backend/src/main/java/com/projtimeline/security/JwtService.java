package com.projtimeline.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secret;
    private final long hours;

    public JwtService(ObjectMapper objectMapper, Clock clock, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        this.hours = properties.jwtHours();
        if (secret.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
    }

    public Token issue(long userId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(hours * 3600);
        try {
            String header = encode(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", Long.toString(userId));
            claims.put("iat", issuedAt.getEpochSecond());
            claims.put("exp", expiresAt.getEpochSecond());
            String payload = encode(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return new Token(signingInput + "." + encode(sign(signingInput)), expiresAt);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to issue JWT", ex);
        }
    }

    public long verifyAndGetUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw BusinessException.unauthorized("登录凭证无效");
            String input = parts[0] + "." + parts[1];
            if (!java.security.MessageDigest.isEqual(sign(input), DECODER.decode(parts[2]))) {
                throw BusinessException.unauthorized("登录凭证无效");
            }
            Map<String, Object> claims = objectMapper.readValue(DECODER.decode(parts[1]), new TypeReference<>() {});
            long exp = ((Number) claims.get("exp")).longValue();
            if (clock.instant().getEpochSecond() >= exp) throw BusinessException.unauthorized("登录已过期");
            return Long.parseLong(claims.get("sub").toString());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.unauthorized("登录凭证无效");
        }
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String encode(byte[] bytes) { return ENCODER.encodeToString(bytes); }

    public record Token(String token, Instant expiresAt) {}
}
