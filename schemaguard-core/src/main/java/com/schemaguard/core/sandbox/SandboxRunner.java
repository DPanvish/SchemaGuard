package com.schemaguard.core.sandbox;

import com.schemaguard.core.sandbox.SandboxExecutionResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxRunner.class);
    public static final String PINNED_POSTGRES_IMAGE = "postgres:16-alpine";

    /**
     * Executes base migrations, optional synthetic seed SQL, and candidate migrations
     * inside a disposable PostgreSQL container with strict resource and timeout limits.
     */
    public SandboxExecutionResult executeInDisposableContainer(
            List<File> baseMigrations,
            List<String> syntheticSeedSqlStatements,
            List<File> candidateMigrations,
            int statementTimeoutSeconds,
            int wallClockTimeoutSeconds) {

        long startTime = System.currentTimeMillis();
        log.info("Starting disposable PostgreSQL sandbox container [stmtTimeout={}s, wallClockTimeout={}s]",
                statementTimeoutSeconds, wallClockTimeoutSeconds);

        try (PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse(PINNED_POSTGRES_IMAGE))
                .withDatabaseName("sg_ephemeral_sandbox")
                .withUsername("sg_sandbox_runner")
                .withPassword("sg_ephemeral_secret")
                .withCreateContainerCmdModifier(cmd -> {
                    if (cmd.getHostConfig() != null) {
                        // Enforce 512MB RAM limit on disposable sandbox container per Section 9 Threat Model
                        cmd.getHostConfig().withMemory(512L * 1024 * 1024);
                    }
                })) {

            container.start();

            try (Connection conn = DriverManager.getConnection(
                    container.getJdbcUrl(), container.getUsername(), container.getPassword())) {

                return executeAgainstConnection(
                        conn,
                        baseMigrations,
                        syntheticSeedSqlStatements,
                        candidateMigrations,
                        statementTimeoutSeconds,
                        wallClockTimeoutSeconds
                );
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String sanitized = redactSecrets(e.getMessage());
            log.error("Disposable container sandbox execution failed after {}ms: {}", duration, sanitized);
            return new SandboxExecutionResult(
                    Status.FAILED,
                    duration,
                    PINNED_POSTGRES_IMAGE,
                    "container-init",
                    null,
                    sanitized,
                    List.of("Sandbox environment failure: " + sanitized)
            );
        }
    }

    /**
     * Executes the migration chain and synthetic fixtures against an isolated JDBC connection
     * with bounded statement and wall-clock timeouts.
     */
    public SandboxExecutionResult executeAgainstConnection(
            Connection connection,
            List<File> baseMigrations,
            List<String> syntheticSeedSqlStatements,
            List<File> candidateMigrations,
            int statementTimeoutSeconds,
            int wallClockTimeoutSeconds) {

        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<SandboxExecutionResult> future = executor.submit(() ->
                runBoundedWorkload(connection, baseMigrations, syntheticSeedSqlStatements,
                        candidateMigrations, statementTimeoutSeconds, startTime)
        );

        try {
            return future.get(Math.max(1, wallClockTimeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = System.currentTimeMillis() - startTime;
            log.warn("Sandbox execution exceeded wall-clock limit of {}s and was terminated", wallClockTimeoutSeconds);
            return new SandboxExecutionResult(
                    Status.TIMED_OUT,
                    duration,
                    "PostgreSQL-sandbox",
                    "wall-clock-limit",
                    "57014",
                    "Sandbox job terminated after exceeding wall-clock timeout of " + wallClockTimeoutSeconds + "s",
                    List.of("Execution exceeded bounded wall-clock timeout (" + wallClockTimeoutSeconds + "s)")
            );
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String sanitized = redactSecrets(e.getMessage());
            return new SandboxExecutionResult(
                    Status.FAILED,
                    duration,
                    "PostgreSQL-sandbox",
                    "unknown",
                    null,
                    sanitized,
                    List.of("Sandbox execution error: " + sanitized)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private SandboxExecutionResult runBoundedWorkload(
            Connection connection,
            List<File> baseMigrations,
            List<String> syntheticSeedSqlStatements,
            List<File> candidateMigrations,
            int statementTimeoutSeconds,
            long startTime) {

        String currentTarget = "initialization";
        String pgVersion = "unknown";
        List<String> evidence = new ArrayList<>();

        try {
            pgVersion = connection.getMetaData().getDatabaseProductVersion();

            // 1. Replay baseline migrations
            if (baseMigrations != null) {
                for (File baseFile : baseMigrations) {
                    currentTarget = baseFile.getName();
                    executeSql(connection, readFile(baseFile), statementTimeoutSeconds);
                }
                evidence.add("Replayed " + baseMigrations.size() + " baseline migration(s)");
            }

            // 2. Load synthetic data fixtures (catches data-dependent NOT NULL / constraint failures)
            if (syntheticSeedSqlStatements != null && !syntheticSeedSqlStatements.isEmpty()) {
                currentTarget = "synthetic-data-fixture";
                for (String seedSql : syntheticSeedSqlStatements) {
                    executeSql(connection, seedSql, statementTimeoutSeconds);
                }
                evidence.add("Loaded " + syntheticSeedSqlStatements.size() + " synthetic fixture statement(s)");
            }

            // 3. Replay candidate migrations
            if (candidateMigrations != null) {
                for (File candFile : candidateMigrations) {
                    currentTarget = candFile.getName();
                    executeSql(connection, readFile(candFile), statementTimeoutSeconds);
                    evidence.add("Successfully verified candidate migration: " + candFile.getName());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Sandbox execution COMPLETED in {}ms", duration);
            return new SandboxExecutionResult(
                    Status.COMPLETED,
                    duration,
                    pgVersion,
                    null,
                    null,
                    null,
                    List.copyOf(evidence)
            );

        } catch (SQLTimeoutException e) {
            long duration = System.currentTimeMillis() - startTime;
            String sanitized = redactSecrets(e.getMessage());
            log.warn("Statement timeout in sandbox during {}: {}", currentTarget, sanitized);
            evidence.add("Statement timed out in " + currentTarget + " after " + statementTimeoutSeconds + "s");
            return new SandboxExecutionResult(
                    Status.TIMED_OUT,
                    duration,
                    pgVersion,
                    currentTarget,
                    e.getSQLState(),
                    sanitized,
                    List.copyOf(evidence)
            );
        } catch (SQLException e) {
            long duration = System.currentTimeMillis() - startTime;
            String sanitized = redactSecrets(e.getMessage());
            log.warn("Candidate migration failed in sandbox during {} [sqlState={}]: {}",
                    currentTarget, e.getSQLState(), sanitized);
            evidence.add("Execution failed in " + currentTarget + ": " + sanitized);
            return new SandboxExecutionResult(
                    Status.FAILED,
                    duration,
                    pgVersion,
                    currentTarget,
                    e.getSQLState(),
                    sanitized,
                    List.copyOf(evidence)
            );
        }
    }

    private void executeSql(Connection conn, String sql, int timeoutSeconds) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(Math.max(1, timeoutSeconds));
            stmt.execute(sql);
        }
    }

    private String readFile(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration file: " + file.getName(), e);
        }
    }

    /**
     * Section 9 Threat Model: Redact connection strings, passwords, and secrets from logs and reports.
     */
    public static String redactSecrets(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }
        return rawMessage
                .replaceAll("(?i)(password|passwd|pwd|secret|token)\\s*=\\s*[^\\s;&]+", "$1=***REDACTED***")
                .replaceAll("jdbc:postgresql://[^\\s]+", "jdbc:postgresql://***REDACTED***");
    }
}