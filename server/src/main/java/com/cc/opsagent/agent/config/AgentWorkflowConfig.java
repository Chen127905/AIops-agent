package com.cc.opsagent.agent.config;

import com.cc.opsagent.agent.application.AgentWorkflowEngine;
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
            DiagnosticToolGateway tools) {
        OpsAgentGraphFactory factory = new OpsAgentGraphFactory(
                new TriageNode(model),
                new RetrieveNode(knowledge),
                new PlanNode(model),
                new DiagnoseNode(tools),
                new DecisionNode(model),
                new VerifyNode(),
                new SummarizeNode());
        return new AlibabaGraphWorkflowEngine(factory);
    }
}
