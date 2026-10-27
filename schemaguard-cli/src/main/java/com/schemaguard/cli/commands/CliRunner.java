package com.schemaguard.cli.commands;

import com.schemaguard.core.dependencies.ServiceContractLoader;
import com.schemaguard.core.policy.PolicyLoader;
import com.schemaguard.core.reporting.AnalysisReport;
import com.schemaguard.core.reporting.SchemaGuardAnalysisOrchestrator;
import com.schemaguard.core.reporting.SchemaGuardAnalysisOrchestrator.AnalysisRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);
    private static final Path DEFAULT_RUNS_DIR = Path.of(".schemaguard", "runs");

    private final SchemaGuardAnalysisOrchestrator orchestrator;
    private final ServiceContractLoader contractLoader;
    private final PolicyLoader policyLoader;
    private int exitCode = 0;

    public CliRunner(
            SchemaGuardAnalysisOrchestrator orchestrator,
            ServiceContractLoader contractLoader,
            PolicyLoader policyLoader) {
        this.orchestrator = orchestrator;
        this.contractLoader = contractLoader;
        this.policyLoader = policyLoader;
    }

    @Override
    public void run(String... args) {
        if (args.length == 0) {
            log.error("No command provided. Available commands: init, check, validate-contracts, report");
            exitCode = 2;
            return;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        Map<String, String> flags = parseFlags(args);
        log.info("Executing CLI command: {} with flags: {}", command, flags);

        try {
            switch (command) {
                case "init" -> handleInitCommand();
                case "check" -> handleCheckCommand(flags);
                case "validate-contracts" -> handleValidateContractsCommand(flags);
                case "report" -> handleReportCommand(flags);
                default -> {
                    log.error("Unknown command: {}. Supported commands: init, check, validate-contracts, report", command);
                    exitCode = 2;
                }
            }
        } catch (Exception e) {
            log.error("CLI execution failed for command '{}': {}", command, e.getMessage(), e);
            exitCode = 2; // Exit code 2: Execution or configuration failure
        }
    }

    private void handleInitCommand() throws IOException {
        Path configDir = Path.of(".schemaguard");
        Files.createDirectories(configDir);
        Files.createDirectories(DEFAULT_RUNS_DIR);

        Path policyPath = configDir.resolve("policy.yaml");
        if (!Files.exists(policyPath)) {
            Files.writeString(policyPath, """
                    version: 1
                    dialect: postgresql
                    policy:
                      unsupportedSql: require-review
                      destructiveChanges: block
                      breakingContracts: block
                      migrationHistoryChanges: block
                    rules:
                      SG001:
                        enabled: true
                        severity: CRITICAL
                      SG005:
                        enabled: true
                        severity: HIGH
                        requireLargeTableProfile: true
                      SG010:
                        enabled: true
                        severity: REVIEW_REQUIRED
                    reports:
                      formats: [markdown, json, sarif]
                    """, StandardCharsets.UTF_8);
        }

        Path contractsPath = configDir.resolve("service-contracts.yaml");
        if (!Files.exists(contractsPath)) {
            Files.writeString(contractsPath, """
                    version: 1
                    services:
                      billing-api:
                        reads:
                          - table: public.customers
                            columns: [id, email]
                    """, StandardCharsets.UTF_8);
        }

        log.info("Initialized .schemaguard workspace with policy.yaml and service-contracts.yaml");
        exitCode = 0;
    }

    private void handleCheckCommand(Map<String, String> flags) {
        String baseRef = flags.getOrDefault("base", "origin/main");
        String headRef = flags.getOrDefault("head", "HEAD");
        String runId = flags.getOrDefault("run-id", "sg-run-latest");

        File baseDir = new File(flags.getOrDefault("base-dir", "test-fixtures/safe-migrations"));
        File candidateDir = new File(flags.getOrDefault("candidate-dir", "test-fixtures/safe-migrations"));
        File contractFile = new File(flags.getOrDefault("contracts", "test-fixtures/sample-contracts/service-contracts.yaml"));
        File policyFile = new File(flags.getOrDefault("policy", "test-fixtures/sample-contracts/policy.yaml"));

        boolean useSandbox = Boolean.parseBoolean(flags.getOrDefault("sandbox", "false"));
        boolean strictMode = Boolean.parseBoolean(flags.getOrDefault("strict", "true"));

        if (!candidateDir.exists() || !candidateDir.isDirectory()) {
            log.error("Candidate migration directory does not exist: {}", candidateDir.getAbsolutePath());
            exitCode = 2;
            return;
        }

        AnalysisRequest request = new AnalysisRequest(
                runId,
                baseRef,
                headRef,
                baseDir,
                candidateDir,
                contractFile,
                policyFile,
                useSandbox,
                strictMode,
                DEFAULT_RUNS_DIR
        );

        AnalysisReport report = orchestrator.executeAnalysis(request);
        this.exitCode = report.exitCode();
    }

    private void handleValidateContractsCommand(Map<String, String> flags) {
        File contractFile = new File(flags.getOrDefault("contracts", "test-fixtures/sample-contracts/service-contracts.yaml"));
        File policyFile = new File(flags.getOrDefault("policy", "test-fixtures/sample-contracts/policy.yaml"));

        if (!contractFile.exists()) {
            log.error("Service contract file not found: {}", contractFile.getAbsolutePath());
            exitCode = 2;
            return;
        }
        contractLoader.loadAndValidate(contractFile);

        if (policyFile.exists()) {
            policyLoader.loadAndValidate(policyFile);
        }

        log.info("Successfully validated service contracts ({}) and policy configuration ({})",
                contractFile.getName(), policyFile.getName());
        exitCode = 0;
    }

    private void handleReportCommand(Map<String, String> flags) throws IOException {
        String runId = flags.getOrDefault("run", "sg-run-latest");
        String format = flags.getOrDefault("format", "markdown").toLowerCase(Locale.ROOT);

        String ext = switch (format) {
            case "json" -> "report.json";
            case "sarif" -> "report.sarif";
            case "markdown", "md" -> "report.md";
            default -> throw new IllegalArgumentException("Unsupported report format: " + format);
        };

        Path reportFile = DEFAULT_RUNS_DIR.resolve(runId).resolve(ext);
        if (!Files.exists(reportFile)) {
            log.error("Report artifact not found at {}", reportFile.toAbsolutePath());
            exitCode = 2;
            return;
        }

        String content = Files.readString(reportFile, StandardCharsets.UTF_8);
        log.info("Loaded {} report for runId={} ({} bytes)", format, runId, content.length());
        exitCode = 0;
    }

    private Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    flags.put(key, args[++i]);
                } else {
                    flags.put(key, "true");
                }
            }
        }
        return flags;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}