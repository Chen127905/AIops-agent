package com.cc.opsagent.knowledge.application;

import java.util.List;

@FunctionalInterface
public interface EmbeddingGateway {

    List<float[]> embed(List<String> texts);
}
