package com.cc.opsagent.ticket.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
interface TicketMapper extends BaseMapper<Ticket> {

    @Select("""
            SELECT id, tenant_id, reporter_id, title, description,
                   affected_service, category, scenario_key, severity, status,
                   resolution_summary, created_at, updated_at
            FROM ticket
            WHERE tenant_id = #{tenantId} AND id = #{ticketId}
            """)
    Ticket selectByTenantIdAndId(
            @Param("tenantId") long tenantId,
            @Param("ticketId") long ticketId);

    @Select("""
            <script>
            SELECT id, tenant_id, reporter_id, title, description,
                   affected_service, category, scenario_key, severity, status,
                   resolution_summary, created_at, updated_at
            FROM ticket
            WHERE tenant_id = #{tenantId}
            <if test="status != null">
              AND status = #{status}
            </if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Ticket> selectPageByTenantId(
            @Param("tenantId") long tenantId,
            @Param("status") TicketStatus status,
            @Param("offset") int offset,
            @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ticket
            WHERE tenant_id = #{tenantId}
            <if test="status != null">
              AND status = #{status}
            </if>
            </script>
            """)
    long countByTenantId(
            @Param("tenantId") long tenantId,
            @Param("status") TicketStatus status);

    @Update("""
            UPDATE ticket
            SET status = #{targetStatus}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId}
              AND id = #{ticketId}
              AND status = #{expectedStatus}
            """)
    int transitionStatus(
            @Param("tenantId") long tenantId,
            @Param("ticketId") long ticketId,
            @Param("expectedStatus") TicketStatus expectedStatus,
            @Param("targetStatus") TicketStatus targetStatus);

    @Update("""
            UPDATE ticket
            SET status = #{targetStatus},
                resolution_summary = #{resolutionSummary},
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId}
              AND id = #{ticketId}
              AND status = #{expectedStatus}
            """)
    int transitionStatusWithResolution(
            @Param("tenantId") long tenantId,
            @Param("ticketId") long ticketId,
            @Param("expectedStatus") TicketStatus expectedStatus,
            @Param("targetStatus") TicketStatus targetStatus,
            @Param("resolutionSummary") String resolutionSummary);
}
