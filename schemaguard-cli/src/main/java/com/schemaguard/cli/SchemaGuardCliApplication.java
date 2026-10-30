package com.schemaguard.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = {"com.schemaguard.cli", "com.schemaguard.core"},
        exclude = {
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class
        }
)
public class SchemaGuardCliApplication {
    
    private static final Logger log = LoggerFactory.getLogger(SchemaGuardCliApplication.class);

    public static void main(String[] args) {
        log.info("Starting SchemaGuard CLI...");
        System.exit(SpringApplication.exit(SpringApplication.run(SchemaGuardCliApplication.class, args)));
    }
}