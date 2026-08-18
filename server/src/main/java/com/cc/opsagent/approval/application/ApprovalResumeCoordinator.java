package com.cc.opsagent.approval.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ApprovalResumeCoordinator implements ApprovalResumeHandler {

    private final ThreadPoolTaskExecutor executor;
    private final ApprovalResumeService resumeService;

    public ApprovalResumeCoordinator(
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            ApprovalResumeService resumeService) {
        this.executor = executor;
        this.resumeService = resumeService;
    }

    @Override
    public void dispatch(ResumeCommand command) {
        executor.execute(() -> resumeService.resume(command));
    }
}
