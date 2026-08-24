package com.cc.opsagent.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdScalarSerializer;

@Configuration(proxyBeanMethods = false)
public class JsonSerializationConfig {

    @Bean
    JsonMapperBuilderCustomizer javaScriptSafeLongs() {
        return builder -> {
            SimpleModule module = new SimpleModule("javascript-safe-longs");
            JavaScriptSafeLongSerializer serializer =
                    new JavaScriptSafeLongSerializer();
            module.addSerializer(Long.class, serializer);
            module.addSerializer(Long.TYPE, serializer);
            builder.addModule(module);
        };
    }

    static final class JavaScriptSafeLongSerializer
            extends StdScalarSerializer<Long> {

        static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

        JavaScriptSafeLongSerializer() {
            super(Long.class);
        }

        @Override
        public void serialize(
                Long value,
                JsonGenerator generator,
                SerializationContext context) throws JacksonException {
            if (value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER) {
                generator.writeNumber(value);
                return;
            }
            generator.writeString(value.toString());
        }
    }
}
