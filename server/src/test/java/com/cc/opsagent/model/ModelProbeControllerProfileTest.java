package com.cc.opsagent.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelProbeControllerProfileTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ProbeDependencyConfig.class,
                            ModelProbeController.class);

    @Test
    void probeControllerIsAbsentOutsideLocalProfile() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(ModelProbeController.class));
    }

    @Test
    void probeControllerIsAvailableInLocalProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context ->
                        assertThat(context).hasSingleBean(ModelProbeController.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeDependencyConfig {

        @Bean
        ModelGateway modelGateway() {
            return mock(ModelGateway.class);
        }
    }
}
