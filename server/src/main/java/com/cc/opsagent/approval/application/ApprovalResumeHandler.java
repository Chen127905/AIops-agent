package com.cc.opsagent.approval.application;

public interface ApprovalResumeHandler {

    void dispatch(ResumeCommand command);
}
