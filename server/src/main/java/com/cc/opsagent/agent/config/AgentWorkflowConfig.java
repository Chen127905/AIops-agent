package com.cc.opsagent.agent.config;

import com.cc.opsagent.agent.application.AgentWorkflowEngine;
import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.AgentEventService;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.application.OpsAgentWorkflow;
import com.cc.opsagent.agent.application.DiagnosticToolGateway;
import com.cc.opsagent.agent.graph.OpsAgentGraphFactory;
import com.cc.opsagent.agent.graph.node.DecisionNode;
import com.cc.opsagent.agent.graph.node.DiagnoseNode;
import com.cc.opsagent.agent.graph.node.PlanNode;
import com.cc.opsagent.agent.graph.node.RetrieveNode;
import com.cc.opsagent.agent.graph.node.SummarizeNode;
import com.cc.opsagent.agent.graph.node.TriageNode;
import com.cc.opsagent.agent.graph.node.VerifyNode;
import com.cc.opsagent.agent.infrastructure.AlibabaGraphWorkflowEngine;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;
import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.ticket.application.TicketService;
import com.cc.opsagent.approval.application.ApprovalRequestCreator;
import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.security.SensitiveDataRedactor;
import com.cc.opsagent.observability.AgentMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class AgentWorkflowConfig {

    @Bean
    AgentWorkflowEngine agentWorkflowEngine(
            ModelGateway model,
            KnowledgeRetriever knowledge,
            DiagnosticToolGateway tools,
            AgentExecutionAudit audit,
            CancellationProbe cancellation,
            SensitiveDataRedactor redactor) {
        OpsAgentGraphFactory factory = new OpsAgentGraphFactory(
                new TriageNode(model, audit, cancellation, redactor),
                new RetrieveNode(knowledge, cancellation),
                new PlanNode(model, audit, cancellation, redactor),
                new DiagnoseNode(tools, audit, cancellation),
                new DecisionNode(model, audit, cancellation, redactor),
                new VerifyNode(),
                new SummarizeNode(),
                audit,
                cancellation);
        return new AlibabaGraphWorkflowEngine(factory);
    }

    @Bean
    OpsAgentWorkflow opsAgentWorkflow(
            AgentTaskService tasks,
            AgentEventService events,
            TicketService tickets,
            AgentWorkflowEngine engine,
            @Value("${app.agent.provider:QWEN}") ModelProvider provider,
            @Value("${app.agent.worker-id:local-agent-worker}") String workerId,
            @Value("${app.agent.lease:PT4M}") java.time.Duration lease,
            ApprovalRequestCreator approvals,
            @Value("${app.agent.approval-ttl:PT30M}") java.time.Duration approvalTtl,
            AgentMetrics metrics) {
        return new OpsAgentWorkflow(
                tasks, events, tickets, engine, provider, workerId, lease,
                approvals, approvalTtl, metrics);
    }
}
