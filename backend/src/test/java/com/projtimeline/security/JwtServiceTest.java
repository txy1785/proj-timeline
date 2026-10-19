package com.projtimeline.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projtimeline.common.BusinessException;
import com.projtimeline.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Test
    void tokenContainsOnlyUserIdentityAndExpiresAfterEightHours() {
        JwtService issuer = service(NOW);
        JwtService.Token token = issuer.issue(42);
        assertThat(issuer.verifyAndGetUserId(token.token())).isEqualTo(42);
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(8 * 3600));
        assertThatThrownBy(() -> service(NOW.plusSeconds(8 * 3600)).verifyAndGetUserId(token.token()))
                .isInstanceOf(BusinessException.class).hasMessage("登录已过期");
    }

    @Test
    void modifiedTokenIsRejected() {
        String token = service(NOW).issue(7).token();
        String modified = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertThatThrownBy(() -> service(NOW).verifyAndGetUserId(modified)).isInstanceOf(BusinessException.class);
    }

    @Test
    void weakSecretIsRejected() {
        assertThatThrownBy(() -> new JwtService(new ObjectMapper(), Clock.systemUTC(), new AppProperties("Asia/Shanghai", "short", 8, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    private JwtService service(Instant instant) {
        return new JwtService(new ObjectMapper(), Clock.fixed(instant, ZoneId.of("Asia/Shanghai")), new AppProperties("Asia/Shanghai", SECRET, 8, false));
    }
}
