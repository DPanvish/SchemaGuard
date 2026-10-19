package com.schemaguard.core.rules;

import com.schemaguard.core.diff.SchemaDiffEngine;
import com.schemaguard.core.diff.SchemaDiffResult;
import com.schemaguard.core.parser.MigrationArtifact;
import com.schemaguard.core.parser.MigrationHistoryValidator.TamperedMigration;
import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.parser.OperationType;
import com.schemaguard.core.parser.ParserStatus;
import com.schemaguard.core.schema.CanonicalSchemaNormalizer;
import com.schemaguard.core.schema.ColumnModel;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableModel;
import com.schemaguard.core.schema.TableObjects.PrimaryKeyModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase4RiskEngineTest {

    private CanonicalSchemaNormalizer normalizer;
    private SchemaDiffEngine diffEngine;
    private RuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        normalizer = new CanonicalSchemaNormalizer();
        diffEngine = new SchemaDiffEngine();
        StandardRiskRules ruleConfig = new StandardRiskRules();

        ruleEngine = new RuleEngine(List.of(
                ruleConfig.sg001DropTableOrColumnRule(),
                ruleConfig.sg002RenameCandidateRule(),
                ruleConfig.sg003IncompatibleTypeChangeRule(),
                ruleConfig.sg004UnsafeNotNullRule(),
                ruleConfig.sg005NonConcurrentIndexRule(),
                ruleConfig.sg006ConstraintLockRule(),
                ruleConfig.sg007ModifiedHistoricalMigrationRule(),
                ruleConfig.sg008BreakingServiceContractRule(),
                ruleConfig.sg009DestructiveDmlRule(),
                ruleConfig.sg010UnsupportedSqlScopeRule(),
                ruleConfig.sg011SchemaDriftRule(),
                ruleConfig.sg012TableRewriteLockRule()
        ));
    }

    @Test
    void shouldProduceZeroFindingsForSafeNullableColumnAddition() {
        TableModel baseTable = new TableModel(
                "customers",
                List.of(new ColumnModel("id", "uuid", false, null, false)),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );
        TableModel candTable = new TableModel(
                "customers",
                List.of(
                        new ColumnModel("id", "uuid", false, null, false),
                        new ColumnModel("preferred_language", "text", true, null, false)
                ),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );

        SchemaSnapshot baseSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(baseTable), List.of(), List.of())));
        SchemaSnapshot candSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(candTable), List.of(), List.of())));

        NormalizedOperation addColOp = NormalizedOperation.create(
                OperationType.ADD_COLUMN, "public", "customers", "preferred_language",
                Map.of("dataType", "TEXT"), "V2__add_preferred_language.sql", 1, 2, ParserStatus.SUPPORTED
        );
        MigrationArtifact artifact = MigrationArtifact.of(
                "V2__add_preferred_language.sql", "V2__add_preferred_language.sql", "hash123", List.of(addColOp)
        );

        SchemaDiffResult diff = diffEngine.compare(baseSnap, candSnap, List.of(addColOp));

        AnalysisContext ctx = new AnalysisContext(
                baseSnap, candSnap, diff, List.of(artifact), List.of(),
                Map.of("public.customers.id", List.of("billing-api")),
                Map.of(), false, List.of(), true
        );

        List<Finding> findings = ruleEngine.evaluateAll(ctx);
        assertTrue(findings.isEmpty(), "Safe nullable column addition should produce zero risk findings");
    }

    @Test
    void shouldTriggerSG001AndSG008WithExpandMigrateContractRemediationWhenDroppingContractedColumn() {
        TableModel baseTable = new TableModel(
                "customers",
                List.of(
                        new ColumnModel("id", "uuid", false, null, false),
                        new ColumnModel("email", "text", false, null, false)
                ),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );
        TableModel candTable = new TableModel(
                "customers",
                List.of(new ColumnModel("id", "uuid", false, null, false)),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );

        SchemaSnapshot baseSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(baseTable), List.of(), List.of())));
        SchemaSnapshot candSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(candTable), List.of(), List.of())));

        NormalizedOperation dropEmailOp = NormalizedOperation.create(
                OperationType.DROP_COLUMN, "public", "customers", "email",
                Map.of(), "V12__remove_email.sql", 1, 2, ParserStatus.SUPPORTED
        );
        MigrationArtifact artifact = MigrationArtifact.of(
                "V12__remove_email.sql", "V12__remove_email.sql", "hash999", List.of(dropEmailOp)
        );

        SchemaDiffResult diff = diffEngine.compare(baseSnap, candSnap, List.of(dropEmailOp));

        AnalysisContext ctx = new AnalysisContext(
                baseSnap, candSnap, diff, List.of(artifact), List.of(),
                Map.of("public.customers.email", List.of("billing-api", "notification-worker")),
                Map.of(), false, List.of(), true
        );

        List<Finding> findings = ruleEngine.evaluateAll(ctx);

        assertEquals(2, findings.size());
        assertEquals("SG001", findings.get(0).ruleId());
        assertEquals(Severity.CRITICAL, findings.get(0).severity());

        Finding sg008 = findings.get(1);
        assertEquals("SG008", sg008.ruleId());
        assertEquals(Severity.CRITICAL, sg008.severity());
        assertEquals("public.customers.email", sg008.objectRef());
        assertEquals("V12__remove_email.sql", sg008.sourceLocation().file());
        assertTrue(sg008.evidence().stream().anyMatch(e -> e.contains("billing-api")));
        assertTrue(sg008.evidence().stream().anyMatch(e -> e.contains("notification-worker")));
        assertTrue(sg008.remediation().contains("Expand-Migrate-Verify-Contract"));
        assertTrue(sg008.remediation().contains("Unverifiable Preconditions"));
    }

    @Test
    void shouldTriggerSG005SG007AndSG010ForOperationalTamperingAndUnsupportedSqlRisks() {
        SchemaSnapshot snap = normalizer.normalizeAndHash("16.2", List.of());
        SchemaDiffResult emptyDiff = diffEngine.compare(snap, snap, List.of());

        NormalizedOperation nonConcurrentIndex = NormalizedOperation.create(
                OperationType.ADD_INDEX, "public", "orders", null,
                Map.of("indexName", "idx_orders_created_at", "concurrently", false),
                "V3__add_index.sql", 1, 1, ParserStatus.SUPPORTED
        );
        NormalizedOperation unsupportedOp = NormalizedOperation.create(
                OperationType.UNKNOWN_OR_UNSUPPORTED, "public", "UNKNOWN", null,
                Map.of("reason", "VACUUM FULL is outside supported DDL subset"),
                "V99__unsupported.sql", 1, 1, ParserStatus.UNSUPPORTED
        );

        MigrationArtifact artifact = MigrationArtifact.of(
                "V3__add_index.sql", "V3__add_index.sql", "hash3", List.of(nonConcurrentIndex, unsupportedOp)
        );

        TamperedMigration tamperedV1 = new TamperedMigration("V1__initial_schema.sql", "old_sha", "new_sha");

        AnalysisContext ctx = new AnalysisContext(
                snap, snap, emptyDiff, List.of(artifact), List.of(tamperedV1),
                Map.of(), Map.of(), false, List.of(), true
        );

        List<Finding> findings = ruleEngine.evaluateAll(ctx);

        assertTrue(findings.stream().anyMatch(f -> "SG007".equals(f.ruleId()) && f.severity() == Severity.CRITICAL));
        assertTrue(findings.stream().anyMatch(f -> "SG005".equals(f.ruleId()) && f.severity() == Severity.HIGH));
        assertTrue(findings.stream().anyMatch(f -> "SG010".equals(f.ruleId()) && f.severity() == Severity.REVIEW_REQUIRED));
    }
}