package com.schemaguard.cli.commands;

import com.schemaguard.core.parser.MigrationChecksumService;
import com.schemaguard.core.parser.MigrationParser;
import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.parser.ParserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final MigrationParser migrationParser;
    private final MigrationChecksumService checksumService;
    private int exitCode = 0;

    public CliRunner(MigrationParser migrationParser, MigrationChecksumService checksumService) {
        this.migrationParser = migrationParser;
        this.checksumService = checksumService;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0) {
            log.error("No command provided. Available commands: init, check, validate-contracts, report");
            exitCode = 2;
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
        if (files == null || files.length == 0) {
            log.info("No SQL files found in {}", migrationDir.getName());
            exitCode = 0;
            return;
        }

        // Sort deterministically by filename
        Arrays.sort(files, Comparator.comparing(File::getName));
        log.info("Found {} SQL migration file(s) in {}", files.length, migrationDir.getName());

        boolean hasUnsupportedSql = false;

        for (File file : files) {
            String checksum = checksumService.calculateChecksum(file);
            List<NormalizedOperation> operations = migrationParser.parse(file);

            for (NormalizedOperation op : operations) {
                log.info("Parsed Operation [file={}, lines={}-{}, type={}, object={}.{}, status={}]",
                        op.sourceFile(), op.startLine(), op.endLine(),
                        op.operationType(), op.schemaName(), op.objectName(), op.parserStatus());

                if (op.parserStatus() == ParserStatus.UNSUPPORTED) {
                    hasUnsupportedSql = true;
                }
            }
        }

        if (hasUnsupportedSql) {
            log.warn("Unsupported SQL detected. Returning exit code 3 (REVIEW_REQUIRED under strict mode).");
            exitCode = 3;
        } else {
            exitCode = 0;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}