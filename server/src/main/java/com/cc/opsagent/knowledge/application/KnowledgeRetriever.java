package com.cc.opsagent.knowledge.application;

import java.util.List;

public interface KnowledgeRetriever {

    List<EvidenceChunk> retrieve(KnowledgeQuery query);
}
