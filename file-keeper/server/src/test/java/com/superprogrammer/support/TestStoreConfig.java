package com.superprogrammer.support;

import com.superprogrammer.user.service.VerificationCodeStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestStoreConfig {

    @Bean
    @Primary
    public VerificationCodeStore verificationCodeStore() {
        return new InMemoryVerificationCodeStore();
    }
}
