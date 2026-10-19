package com.projtimeline;

import com.projtimeline.config.AppProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@MapperScan("com.projtimeline.user")
@EnableConfigurationProperties(AppProperties.class)
public class TimelineApplication {
    public static void main(String[] args) {
        SpringApplication.run(TimelineApplication.class, args);
    }

    @Bean
    Clock businessClock(AppProperties properties) {
        return Clock.system(ZoneId.of(properties.timeZone()));
    }
}
