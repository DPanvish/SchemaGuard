package com.schemaguard.core.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.schemaguard.core.parser.JSqlParserAdapter;
import com.schemaguard.core.parser.MigrationChecksumService;
import com.schemaguard.core.parser.MigrationHistoryValidator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase2SqlAnalysisTest {

    private JSqlParserAdapter parser;
    private MigrationChecksumService checksumService;
    private MigrationHistoryValidator historyValidator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new JSqlParserAdapter();
        checksumService = new MigrationChecksumService();
        historyValidator = new MigrationHistoryValidator();
    }

    @Test
    void shouldParseSupportedCreateAndAlterStatements() throws IOException {
        Path sqlFile = tempDir.resolve("V1__test_supported.sql");
        Files.writeString(sqlFile, """
                CREATE TABLE public.customers (
                    id UUID PRIMARY KEY,
                    email TEXT NOT NULL
                );
                ALTER TABLE public.customers ADD COLUMN preferred_language TEXT;
                ALTER TABLE public.customers DROP COLUMN email;
                """);

        List<NormalizedOperation> ops = parser.parse(sqlFile.toFile());

        assertEquals(3, ops.size());
        assertEquals(OperationType.CREATE_TABLE, ops.get(0).operationType());
        assertEquals(ParserStatus.SUPPORTED, ops.get(0).parserStatus());

        assertEquals(OperationType.ADD_COLUMN, ops.get(1).operationType());
        assertEquals("preferred_language", ops.get(1).columnName());

        assertEquals(OperationType.DROP_COLUMN, ops.get(2).operationType());
        assertEquals("email", ops.get(2).columnName());
    }

    @Test
    void shouldExplicitlyFlagUnsupportedSqlAndNeverSilentlyPass() throws IOException {
        Path sqlFile = tempDir.resolve("V99__unsupported.sql");
        Files.writeString(sqlFile, "VACUUM FULL public.customers;");

        File file = sqlFile.toFile();
        List<NormalizedOperation> ops = parser.parse(file);
        MigrationArtifact artifact = MigrationArtifact.of(
                file.getName(), file.getPath(), checksumService.calculateChecksum(file), ops);

        assertEquals(1, ops.size());
        assertEquals(OperationType.UNKNOWN_OR_UNSUPPORTED, ops.get(0).operationType());
        assertEquals(ParserStatus.UNSUPPORTED, ops.get(0).parserStatus());
        assertEquals(ParserStatus.UNSUPPORTED, artifact.overallStatus());
    }

    @Test
    void shouldDetectModifiedHistoricalMigrationViaChecksum() throws IOException {
        Path v1File = tempDir.resolve("V1__initial_schema.sql");
        Files.writeString(v1File, "CREATE TABLE public.customers (id UUID PRIMARY KEY);");
        String baselineChecksum = checksumService.calculateChecksum(v1File.toFile());

        // Simulate developer editing an already-applied migration file
        Files.writeString(v1File, "CREATE TABLE public.customers (id UUID PRIMARY KEY, email TEXT);");
        String candidateChecksum = checksumService.calculateChecksum(v1File.toFile());
        MigrationArtifact candidateArtifact = MigrationArtifact.of(
                v1File.getFileName().toString(),
                v1File.toString(),
                candidateChecksum,
                parser.parse(v1File.toFile())
        );

        List<MigrationHistoryValidator.TamperedMigration> tampered = historyValidator.detectModifiedHistory(
                Map.of("V1__initial_schema.sql", baselineChecksum),
                List.of(candidateArtifact)
        );

        assertEquals(1, tampered.size());
        assertEquals("V1__initial_schema.sql", tampered.get(0).fileName());
    }
}