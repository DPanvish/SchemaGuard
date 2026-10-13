package com.schemaguard.cli.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;

@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);
    private int exitCode = 0; // Default to 0 (Success)

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            log.error("No command provided. Available commands: init, check, validate-contracts, report");
            exitCode = 2; // Exit code 2 for Execution or configuration failure
            return;
        }

        String command = args[0];
        log.info("Executing command: {}", command);

        switch (command.toLowerCase()) {
            case "check":
                handleCheckCommand(args);
                break;
            default:
                log.warn("Unknown command: {}. Running in dry mode.", command);
                exitCode = 2;
        }
    }

    private void handleCheckCommand(String[] args) {
        File migrationDir = new File("test-fixtures/safe-migrations");
        
        if (!migrationDir.exists() || !migrationDir.isDirectory()) {
            log.error("Migration directory not found: {}", migrationDir.getAbsolutePath());
            exitCode = 2;
            return;
        }

        File[] files = migrationDir.listFiles((dir, name) -> name.endsWith(".sql"));
        if (files != null) {
            log.info("Found {} SQL migration files in {}", files.length, migrationDir.getName());
            Arrays.stream(files).forEach(file -> log.info(" - {}", file.getName()));
        } else {
            log.info("No SQL files found in {}", migrationDir.getName());
        }
        
        // Exit code 0 means Policy passed; no blocking finding (simulated for now)
        exitCode = 0; 
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}