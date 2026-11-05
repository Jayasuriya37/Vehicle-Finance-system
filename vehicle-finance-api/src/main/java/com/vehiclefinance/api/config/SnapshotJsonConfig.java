package com.vehiclefinance.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class SnapshotJsonConfig {
    @Bean(name = "stateSnapshotObjectMapper")
    public ObjectMapper stateSnapshotObjectMapper() {
        return new ObjectMapper();
    }
}
