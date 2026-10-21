package com.schemaguard.core.dependencies;

import com.schemaguard.core.dependencies.DependencyGraph.ImpactAssessment;
import com.schemaguard.core.dependencies.ServiceContractLoader.LoadedContractArtifact;
import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.parser.OperationType;
import com.schemaguard.core.parser.ParserStatus;
import com.schemaguard.core.sandbox.SandboxExecutionResult;
import com.schemaguard.core.sandbox.SandboxExecutionResult.Status;
import com.schemaguard.core.sandbox.SandboxRunner;
import com.schemaguard.core.schema.CanonicalSchemaNormalizer;
import com.schemaguard.core.schema.ColumnModel;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableModel;
import com.schemaguard.core.schema.TableObjects.ForeignKeyModel;
import com.schemaguard.core.schema.TableObjects.PrimaryKeyModel;
import com.schemaguard.core.schema.TableObjects.ViewModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase5DependencyAndSandboxTest {

    private ServiceContractLoader contractLoader;
    private DependencyGraphBuilder graphBuilder;
    private CanonicalSchemaNormalizer normalizer;
    private SandboxRunner sandboxRunner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        contractLoader = new ServiceContractLoader();
        graphBuilder = new DependencyGraphBuilder();
        normalizer = new CanonicalSchemaNormalizer();
        sandboxRunner = new SandboxRunner();
    }

    @Test
    void shouldLoadServiceContractsAndComputeAffectedConsumersAndCatalogDependencies() {
        String yaml = """
                version: 1
                services:
                  billing-api:
                    reads:
                      - table: public.customers
                        columns: [id, email]
                  notification-worker:
                    reads:
                      - table: public.customers
                        columns: [email]
                  reporting-service:
                    reads:
                      - table: public.customers
                        columns: [id, name]
                """;

        LoadedContractArtifact contract = contractLoader.loadAndValidateFromString("service-contracts.yaml", yaml);

        TableModel customers = new TableModel(
                "customers",
                List.of(
                        new ColumnModel("id", "uuid", false, null, false),
                        new ColumnModel("email", "text", true, null, false),
                        new ColumnModel("name", "text", true, null, false)
                ),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );

        TableModel invoices = new TableModel(
                "invoices",
                List.of(
                        new ColumnModel("id", "uuid", false, null, false),
                        new ColumnModel("customer_id", "uuid", false, null, false)
                ),
                new PrimaryKeyModel("invoices_pkey", List.of("id")),
                List.of(),
                List.of(new ForeignKeyModel(
                        "fk_invoices_customer",
                        List.of("customer_id"),
                        "public",
                        "customers",
                        List.of("id"),
                        "NO ACTION",
                        "NO ACTION"
                )),
                List.of(),
                List.of()
        );

        ViewModel activeCustomersView = new ViewModel(
                "v_active_customers",
                "SELECT id, email FROM public.customers"
        );

        SchemaSnapshot baseSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(customers, invoices), List.of(activeCustomersView), List.of())));

        NormalizedOperation dropEmailOp = NormalizedOperation.create(
                OperationType.DROP_COLUMN, "public", "customers", "email",
                Map.of(), "V12__remove_email.sql", 1, 2, ParserStatus.SUPPORTED
        );

        DependencyGraph graph = graphBuilder.build(baseSnap, contract.manifest(), List.of(dropEmailOp));

        // 1. Check column-level impact for public.customers.email
        ImpactAssessment emailImpact = graph.assessImpact("public.customers.email");
        assertEquals(List.of("billing-api", "notification-worker"), emailImpact.affectedServices());
        assertTrue(emailImpact.hasContractCoverage());

        // 2. Check table-level impact for public.customers (includes all 3 services + FK + View)
        ImpactAssessment tableImpact = graph.assessImpact("public.customers");
        assertEquals(List.of("billing-api", "notification-worker", "reporting-service"), tableImpact.affectedServices());
        assertTrue(tableImpact.affectedDatabaseObjects().contains("public.invoices"));
        assertTrue(tableImpact.affectedDatabaseObjects().contains("public.v_active_customers"));

        // 3. Check coverage gap on public.invoices (no service contract declared for invoices)
        ImpactAssessment invoiceImpact = graph.assessImpact("public.invoices.customer_id");
        assertFalse(invoiceImpact.hasContractCoverage());
        assertTrue(invoiceImpact.supportingEvidence().stream().anyMatch(e -> e.contains("Coverage gap")));
    }

    @Test
    void shouldRejectUnknownYamlFieldsAndUnsupportedContractVersions() {
        String unknownFieldYaml = """
                version: 1
                unknownTopLevelField: true
                services:
                  billing-api:
                    reads:
                      - table: public.customers
                        columns: [id]
                """;

        assertThrows(IllegalArgumentException.class, () ->
                contractLoader.loadAndValidateFromString("bad-contract.yaml", unknownFieldYaml));

        String unsupportedVersionYaml = """
                version: 99
                services:
                  billing-api:
                    reads:
                      - table: public.customers
                        columns: [id]
                """;

        assertThrows(IllegalArgumentException.class, () ->
                contractLoader.loadAndValidateFromString("v99-contract.yaml", unsupportedVersionYaml));
    }

    @Test
    void shouldCaptureDataDependentSandboxFailureAndRedactCredentials() throws Exception {
        Path v1File = tempDir.resolve("V1__initial_schema.sql");
        Files.writeString(v1File, "CREATE TABLE public.customers (id UUID PRIMARY KEY, email TEXT NULL);");

        Path v13File = tempDir.resolve("V13__set_email_not_null.sql");
        Files.writeString(v13File, "ALTER TABLE public.customers ALTER COLUMN email SET NOT NULL;");

        // Simulate an isolated JDBC connection where candidate SET NOT NULL fails due to existing NULL rows
        Connection mockConnection = createSimulatedJdbcConnection(sql -> {
            if (sql.contains("SET NOT NULL")) {
                throw new SQLException(
                        "ERROR: column \"email\" of relation \"customers\" contains null values " +
                        "(conn=jdbc:postgresql://localhost:5432/db?password=super_secret_pw)",
                        "23502"
                );
            }
        });

        SandboxExecutionResult result = sandboxRunner.executeAgainstConnection(
                mockConnection,
                List.of(v1File.toFile()),
                List.of("INSERT INTO public.customers (id, email) VALUES ('00000000-0000-0000-0000-000000000001', NULL);"),
                List.of(v13File.toFile()),
                5,
                10
        );

        assertEquals(Status.FAILED, result.status());
        assertEquals("V13__set_email_not_null.sql", result.failedMigrationFile());
        assertEquals("23502", result.sqlState());
        assertTrue(result.sanitizedErrorMessage().contains("contains null values"));
        assertFalse(result.sanitizedErrorMessage().contains("super_secret_pw"), "Password must be redacted");
        assertTrue(result.sanitizedErrorMessage().contains("***REDACTED***"));
    }

    @FunctionalInterface
    private interface SqlBehaviour {
        void onExecute(String sql) throws SQLException;
    }

    private Connection createSimulatedJdbcConnection(SqlBehaviour behaviour) {
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> "16.2-sandbox"
        );

        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName()) && args != null && args.length > 0) {
                        behaviour.onExecute(String.valueOf(args[0]));
                        return true;
                    }
                    return null;
                }
        );

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) return metaData;
                    if ("createStatement".equals(method.getName())) return statement;
                    return null;
                }
        );
    }
}