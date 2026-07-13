package io.okdocs.compliance.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCdpProfileConfigTest {

    private static final String ENABLED = "compliance.crawler.dynamic.enabled";
    private static final String PREMIUM_ENABLED = "compliance.crawler.dynamic.premium-enabled";

    @Test
    void localProfileKeepsCdpDisabledByDefault() {
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty(ENABLED, Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty(PREMIUM_ENABLED, Boolean.class)).isFalse();
        });
    }

    @Test
    void localProfileCanEnableCdpThroughDocumentedEnvironmentAliases() {
        runner()
                .withPropertyValues("CDP_ENABLED=true", "CDP_PREMIUM_ENABLED=true")
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty(ENABLED, Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty(PREMIUM_ENABLED, Boolean.class)).isTrue();
                });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local");
    }
}
