package com.cc.opsagent.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ModelProviderUnavailableException extends IllegalStateException {

    public ModelProviderUnavailableException(ModelProvider provider) {
        super("Model provider is not configured: " + provider);
    }
}
