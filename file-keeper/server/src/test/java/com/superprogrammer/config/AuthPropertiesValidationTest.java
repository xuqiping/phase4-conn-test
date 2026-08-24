package com.superprogrammer.config;

import com.superprogrammer.authorization.service.SignedEntitlementSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPropertiesValidationTest {

    private static final String TEST_ENTITLEMENT_PRIVATE_KEY_PEM =
            SignedEntitlementSigner.encodePrivateKeyPem(SignedEntitlementSigner.generateKeyPair().getPrivate());

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AuthPropertiesConfig.class)
            .withPropertyValues(
                    "file-keeper.auth.jwt.access-token-minutes=15",
                    "file-keeper.auth.refresh-token.days=7",
                    "file-keeper.auth.entitlement.private-key-pem=" + TEST_ENTITLEMENT_PRIVATE_KEY_PEM
            );

    @Test
    void rejectsDefaultJwtSecretOutsideTestProfile() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "file-keeper.auth.jwt.secret=change-this-file-keeper-jwt-secret-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("JWT secret must not use the default development value");
                });
    }

    @Test
    void rejectsShortJwtSecretOutsideTestProfile() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "file-keeper.auth.jwt.secret=too-short"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("JWT secret must be at least 32 UTF-8 bytes");
                });
    }

    @Test
    void acceptsApplicationTestJwtSecretUnderTestProfile() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "file-keeper.auth.jwt.secret=test-file-keeper-jwt-secret-at-least-32-bytes"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthProperties.class);
                });
    }

    @Test
    void acceptsMissingEntitlementPrivateKey() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "file-keeper.auth.jwt.secret=production-file-keeper-jwt-secret-at-least-32-bytes",
                        "file-keeper.auth.entitlement.private-key-pem="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthProperties.class);
                    assertThat(context.getBean(AuthProperties.class).getEntitlementPrivateKey()).isNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class AuthPropertiesConfig {
    }
}
