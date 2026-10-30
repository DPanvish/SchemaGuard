package com.schemaguard.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.schemaguard")
public class SchemaGuardApiApplication {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuardApiApplication.class);

    public static void main(String[] args) {
        log.info("Starting SchemaGuard API & Enterprise Dashboard Server...");
        SpringApplication.run(SchemaGuardApiApplication.class, args);
    }
}