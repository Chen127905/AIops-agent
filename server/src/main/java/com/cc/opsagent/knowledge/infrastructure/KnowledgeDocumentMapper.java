package com.cc.opsagent.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cc.opsagent.knowledge.domain.KnowledgeDocument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Insert("""
            INSERT INTO knowledge_document
                (tenant_id, name, source, media_type, active_version,
                 processing_version, status)
            VALUES
                (#{tenantId}, #{name}, #{source}, #{mediaType}, 0, 1, 'PROCESSING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertNew(KnowledgeDocument document);

    @Select("""
            SELECT id, tenant_id, name, source, media_type, active_version,
                   processing_version, status, created_at, updated_at
            FROM knowledge_document
            WHERE tenant_id = #{tenantId} AND id = #{documentId}
            FOR UPDATE
            """)
    KnowledgeDocument selectForUpdate(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId);

    @Update("""
            UPDATE knowledge_document
            SET processing_version = #{version},
                status = 'PROCESSING',
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId}
              AND id = #{documentId}
              AND processing_version IS NULL
            """)
    int beginVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("version") int version);

    @Insert("""
            INSERT INTO knowledge_document_version
                (document_id, tenant_id, version, status, content_hash,
                 chunk_count, metadata)
            VALUES
                (#{documentId}, #{tenantId}, #{version}, 'PROCESSING',
                 #{contentHash}, 0, CAST(#{metadataJson} AS JSON))
            """)
    int insertVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("version") int version,
            @Param("contentHash") String contentHash,
            @Param("metadataJson") String metadataJson);

    @Update("""
            UPDATE knowledge_document_version
            SET status = 'PUBLISHED',
                chunk_count = #{chunkCount},
                published_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId}
              AND document_id = #{documentId}
              AND version = #{version}
              AND status = 'PROCESSING'
            """)
    int publishVersionRecord(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("version") int version,
            @Param("chunkCount") int chunkCount);

    @Update("""
            UPDATE knowledge_document
            SET active_version = #{version},
                processing_version = NULL,
                status = 'PUBLISHED',
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId}
              AND id = #{documentId}
              AND processing_version = #{version}
            """)
    int publishDocument(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("version") int version);
}
