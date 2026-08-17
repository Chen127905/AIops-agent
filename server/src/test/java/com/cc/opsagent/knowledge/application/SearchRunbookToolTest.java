package com.cc.opsagent.knowledge.application;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.tool.application.ToolPolicyService;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRunbookToolTest {

    @BeforeEach
    void authenticateTenant() {
        TenantPrincipal principal = new TenantPrincipal(
                1L, 10L, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCitedEvidenceThroughTheReadOnlyToolEnvelope() {
        AtomicReference<KnowledgeQuery> captured = new AtomicReference<>();
        EvidenceChunk evidence = new EvidenceChunk(
                1, 9, 2, 3, "runbooks/redis.md", "restart steps",
                Map.of(), 0.98, "tenant:1:doc:9:v2:chunk:3");
        KnowledgeRetriever retriever = query -> {
            assertThat(TenantContext.requireTenantId()).isEqualTo(1L);
            captured.set(query);
            return List.of(evidence);
        };
        SearchRunbookTool tool = new SearchRunbookTool(
                new ToolPolicyService(), retriever);

        var result = tool.searchRunbook(
                new OpsContext(1, 100, "redis-timeout"), "redis timeout", 3);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(result.risk()).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(result.data()).containsExactly(evidence);
        assertThat(captured.get()).isEqualTo(new KnowledgeQuery("redis timeout", 3));
    }

    @Test
    void rejectsAContextForAnotherTenantBeforeRetrieval() {
        AtomicReference<KnowledgeQuery> captured = new AtomicReference<>();
        SearchRunbookTool tool = new SearchRunbookTool(
                new ToolPolicyService(), query -> {
                    captured.set(query);
                    return List.of();
                });

        var result = tool.searchRunbook(
                new OpsContext(2, 100, "redis-timeout"), "redis timeout", 3);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.REJECTED);
        assertThat(result.message()).contains("tenant");
        assertThat(captured).hasNullValue();
    }
}
