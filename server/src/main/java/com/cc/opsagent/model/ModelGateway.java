package com.cc.opsagent.model;

import reactor.core.publisher.Flux;

public interface ModelGateway {

    ModelReply call(ModelProvider provider, ModelRequest request);

    Flux<String> stream(ModelProvider provider, ModelRequest request);
}
