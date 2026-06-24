package com.superprogrammer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Value("${work-report.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
