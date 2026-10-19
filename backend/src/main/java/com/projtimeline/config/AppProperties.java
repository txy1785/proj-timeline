package com.projtimeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String timeZone, String jwtSecret, long jwtHours, boolean bootstrapAdmin) {
    public AppProperties {
        if (timeZone == null || timeZone.isBlank()) timeZone = "Asia/Shanghai";
        if (jwtHours <= 0) jwtHours = 8;
    }
}
