package com.cc.opsagent.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class AgentExecutorConfig {

    @Bean("agentTaskExecutor")
    ThreadPoolTaskExecutor agentTaskExecutor(
            @Value("${app.agent.executor.core-size:2}") int coreSize,
            @Value("${app.agent.executor.max-size:8}") int maxSize,
            @Value("${app.agent.executor.queue-capacity:32}") int queueCapacity) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 0) {
            throw new IllegalArgumentException("invalid agent executor bounds");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-worker-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setTaskDecorator(securityContextTaskDecorator());
        executor.initialize();
        return executor;
    }

    private TaskDecorator securityContextTaskDecorator() {
        return task -> {
            SecurityContext captured = SecurityContextHolder.createEmptyContext();
            captured.setAuthentication(
                    SecurityContextHolder.getContext().getAuthentication());
            return () -> {
                try {
                    SecurityContextHolder.setContext(captured);
                    task.run();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
        };
    }
}
