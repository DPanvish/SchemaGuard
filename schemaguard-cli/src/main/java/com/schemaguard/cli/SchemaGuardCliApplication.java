package com.schemaguard.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.schemaguard")
public class SchemaGuardCliApplication {
    
    private static final Logger log = LoggerFactory.getLogger(SchemaGuardCliApplication.class);

    public static void main(String[] args) {
        log.info("Starting SchemaGuard CLI...");
        System.exit(SpringApplication.exit(SpringApplication.run(SchemaGuardCliApplication.class, args)));
    }
}