package com.schemaguard.cli;

import com.schemaguard.cli.commands.CliRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class SchemaGuardCliApplicationTest {

    @Autowired
    private CliRunner cliRunner;

    @Test
    void contextLoads() {
    }

    @Test
    void testSafeCheckCommandSucceeds() {
        String prefix = resolveProjectRootPrefix();
        cliRunner.run(
                "check",
                "--run-id", "test-safe-check",
                "--base-dir", prefix + "examples/sample-company/baseline-migrations",
                "--candidate-dir", prefix + "examples/sample-company/safe-candidate",
                "--contracts", prefix + "test-fixtures/sample-contracts/service-contracts.yaml",
                "--policy", prefix + "test-fixtures/sample-contracts/policy.yaml"
        );
        assertEquals(0, cliRunner.getExitCode());
    }

    @Test
    void testUnsafeCheckCommandBlocksWithExitCode1() {
        String prefix = resolveProjectRootPrefix();
        cliRunner.run(
                "check",
                "--run-id", "test-unsafe-check",
                "--base-dir", prefix + "examples/sample-company/baseline-migrations",
                "--candidate-dir", prefix + "examples/sample-company/unsafe-candidate",
                "--contracts", prefix + "test-fixtures/sample-contracts/service-contracts.yaml",
                "--policy", prefix + "test-fixtures/sample-contracts/policy.yaml"
        );
        assertEquals(1, cliRunner.getExitCode());
    }

    private String resolveProjectRootPrefix() {
        if (new File("examples/sample-company/baseline-migrations").exists()) {
            return "";
        }
        if (new File("../examples/sample-company/baseline-migrations").exists()) {
            return "../";
        }
        return "";
    }
}

