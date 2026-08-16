package com.cc.opsagent.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

    @Bean(name = "businessFlyway", initMethod = "migrate")
    public Flyway businessFlyway(
            @Qualifier("businessDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/mysql")
                .load();
    }
}
