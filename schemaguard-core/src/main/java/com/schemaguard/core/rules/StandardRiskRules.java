package com.schemaguard.core.rules;

import com.schemaguard.core.diff.ChangeType;
import com.schemaguard.core.diff.SchemaChange;
import com.schemaguard.core.parser.MigrationHistoryValidator.TamperedMigration;
import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.parser.OperationType;
import com.schemaguard.core.parser.ParserStatus;
import com.schemaguard.core.rules.Finding.SourceLocation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Configuration
public class StandardRiskRules {

    private static final Set<String> NARROWING_OR_RISKY_TYPE_TRANSITIONS = Set.of(
            "bigint->integer",
            "bigint->smallint",
            "integer->smallint",
            "text->character varying",
            "double precision->real",
            "timestamp with time zone->timestamp without time zone"
    );

    /**
     * SG001: Drop table or column; evaluate declared consumers, constraints, views, and criticality.
     */
    @Bean
    public SchemaGuardRule sg001DropTableOrColumnRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG001"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    if (change.changeType() == ChangeType.TABLE_DROPPED || change.changeType() == ChangeType.COLUMN_DROPPED) {
                        String obj = change.fullyQualifiedObject();
                        List<String> consumers = ctx.getConsumersFor(obj);
                        TableProfile profile = ctx.getTableProfile(change.schemaName(), change.tableName());

                        List<String> evidence = new ArrayList<>();
                        evidence.add("Candidate schema drops " + obj);
                        if (!consumers.isEmpty()) {
                            consumers.forEach(c -> evidence.add(c + " declares a read dependency on " + obj));
                        }
                        if (profile.productionCritical()) {
                            evidence.add("Table " + profile.fullyQualifiedTable() + " is marked production-critical");
                        }

                        Severity severity = (!consumers.isEmpty() || profile.productionCritical())
                                ? Severity.CRITICAL : Severity.HIGH;

                        findings.add(new Finding(
                                id(),
                                severity,
                                "Destructive drop of database object",
                                "Proposed migration drops " + obj,
                                obj,
                                new SourceLocation(change.sourceFile(), change.sourceLine()),
                                evidence,
                                RemediationTemplates.expandMigrateContractForColumn(obj),
                                consumers.isEmpty() ? "STATIC_DIFF_ONLY" : "CONFIRMED_CONTRACT_IMPACT"
                        ));
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG002: Rename candidate or drop/add sequence affects a declared consumer.
     */
    @Bean
    public SchemaGuardRule sg002RenameCandidateRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG002"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    if (change.changeType() == ChangeType.RENAME_CANDIDATE) {
                        String oldCol = change.schemaName() + "." + change.tableName() + "." + change.beforeValue();
                        List<String> consumers = ctx.getConsumersFor(oldCol);

                        List<String> evidence = new ArrayList<>();
                        evidence.add("Detected drop of '" + change.beforeValue() + "' and addition of '" + change.afterValue() + "' with matching type");
                        evidence.add("Rename cannot be inferred as certain from snapshots alone");
                        consumers.forEach(c -> evidence.add(c + " depends on old column " + oldCol));

                        Severity severity = consumers.isEmpty() ? Severity.REVIEW_REQUIRED : Severity.CRITICAL;
                        findings.add(new Finding(
                                id(),
                                severity,
                                "Potential breaking object rename",
                                "Possible rename on " + change.schemaName() + "." + change.tableName() + ": " + change.objectName(),
                                oldCol,
                                new SourceLocation(change.sourceFile(), change.sourceLine()),
                                evidence,
                                RemediationTemplates.expandMigrateContractForColumn(oldCol),
                                "HEURISTIC_RENAME_CANDIDATE"
                        ));
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG003: Change to a potentially incompatible or narrowing data type.
     */
    @Bean
    public SchemaGuardRule sg003IncompatibleTypeChangeRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG003"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    if (change.changeType() == ChangeType.COLUMN_TYPE_CHANGED) {
                        String transition = (change.beforeValue() + "->" + change.afterValue()).toLowerCase();
                        boolean narrowing = NARROWING_OR_RISKY_TYPE_TRANSITIONS.contains(transition)
                                || !change.beforeValue().equalsIgnoreCase(change.afterValue());

                        if (narrowing) {
                            String obj = change.fullyQualifiedObject();
                            findings.add(new Finding(
                                    id(),
                                    Severity.HIGH,
                                    "Potentially incompatible or narrowing data type change",
                                    "Column " + obj + " changes type from " + change.beforeValue() + " to " + change.afterValue(),
                                    obj,
                                    new SourceLocation(change.sourceFile(), change.sourceLine()),
                                    List.of(
                                            "Before type: " + change.beforeValue(),
                                            "After type: " + change.afterValue()
                                    ),
                                    RemediationTemplates.expandMigrateContractForColumn(obj),
                                    "CONFIRMED_SCHEMA_DIFF"
                            ));
                        }
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG004: Add NOT NULL or required column without default/backfill strategy.
     */
    @Bean
    public SchemaGuardRule sg004UnsafeNotNullRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG004"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    String obj = change.fullyQualifiedObject();
                    if (change.changeType() == ChangeType.COLUMN_NULLABILITY_CHANGED
                            && "NOT NULL".equalsIgnoreCase(change.afterValue())) {
                        findings.add(new Finding(
                                id(),
                                Severity.HIGH,
                                "Setting column NOT NULL requires existing-row validation",
                                "Column " + obj + " changed from NULLABLE to NOT NULL without staged constraint validation",
                                obj,
                                new SourceLocation(change.sourceFile(), change.sourceLine()),
                                List.of(
                                        "Column nullability changed: " + change.beforeValue() + " -> " + change.afterValue(),
                                        "PostgreSQL SET NOT NULL verifies existing rows contain no NULL values"
                                ),
                                RemediationTemplates.safeNotNullRollout(obj),
                                "CONFIRMED_SCHEMA_DIFF"
                        ));
                    } else if (change.changeType() == ChangeType.COLUMN_ADDED
                            && change.afterValue() != null
                            && change.afterValue().endsWith("NOT NULL")) {
                        findings.add(new Finding(
                                id(),
                                Severity.WARN,
                                "Adding required NOT NULL column",
                                "New column " + obj + " is declared NOT NULL; verify default expression or empty-table assumption",
                                obj,
                                new SourceLocation(change.sourceFile(), change.sourceLine()),
                                List.of("Added column specification: " + change.afterValue()),
                                RemediationTemplates.safeNotNullRollout(obj),
                                "CONFIRMED_SCHEMA_DIFF"
                        ));
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG005: Create index without CONCURRENTLY on a table designated large/critical/unknown.
     */
    @Bean
    public SchemaGuardRule sg005NonConcurrentIndexRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG005"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                for (NormalizedOperation op : ctx.allOperations()) {
                    if (op.operationType() == OperationType.ADD_INDEX) {
                        boolean concurrent = Boolean.TRUE.equals(op.arguments().get("concurrently"));
                        TableProfile profile = ctx.getTableProfile(op.schemaName(), op.objectName());

                        if (!concurrent && profile.isLargeOrUnknownOrCritical()) {
                            String tableRef = op.schemaName() + "." + op.objectName();
                            findings.add(new Finding(
                                    id(),
                                    Severity.HIGH,
                                    "Index creation without CONCURRENTLY",
                                    "CREATE INDEX on " + tableRef + " omits CONCURRENTLY and may block writes",
                                    tableRef,
                                    new SourceLocation(op.sourceFile(), op.startLine()),
                                    List.of(
                                            "Index operation omits CONCURRENTLY keyword",
                                            "Table size profile is " + profile.sizeClass() + " (missing size is treated as UNKNOWN, not small)"
                                    ),
                                    RemediationTemplates.concurrentIndexCreation(tableRef),
                                    "OPERATIONAL_RISK_ANALYSIS"
                            ));
                        }
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG006: Add or validate constraint likely to require table scan or lock.
     */
    @Bean
    public SchemaGuardRule sg006ConstraintLockRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG006"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    if (change.changeType() == ChangeType.FOREIGN_KEY_ADDED
                            || change.changeType() == ChangeType.CHECK_CONSTRAINT_ADDED
                            || change.changeType() == ChangeType.UNIQUE_CONSTRAINT_ADDED) {
                        String obj = change.fullyQualifiedObject();
                        findings.add(new Finding(
                                id(),
                                Severity.WARN,
                                "Constraint addition may scan existing rows and acquire locks",
                                "Adding constraint " + obj + " on " + change.schemaName() + "." + change.tableName() + " may scan table rows",
                                obj,
                                new SourceLocation(change.sourceFile(), change.sourceLine()),
                                List.of("Constraint added: " + change.changeType() + " (" + change.afterValue() + ")"),
                                "Add foreign key or check constraints using NOT VALID first, then run VALIDATE CONSTRAINT in a separate transaction.",
                                "OPERATIONAL_RISK_ANALYSIS"
                        ));
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG007: Change a migration file recorded as already applied or released.
     */
    @Bean
    public SchemaGuardRule sg007ModifiedHistoricalMigrationRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG007"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.tamperedMigrations() == null) return findings;

                for (TamperedMigration tampered : ctx.tamperedMigrations()) {
                    findings.add(new Finding(
                            id(),
                            Severity.CRITICAL,
                            "Modified historical migration file",
                            "Historical migration " + tampered.fileName() + " was modified after baseline recording",
                            tampered.fileName(),
                            new SourceLocation(tampered.fileName(), 1),
                            List.of(
                                    "Expected baseline SHA-256: " + tampered.expectedBaselineChecksum(),
                                    "Actual candidate SHA-256: " + tampered.actualCandidateChecksum()
                            ),
                            "Revert changes to " + tampered.fileName() + " and create a new forward versioned migration file instead.",
                            "CHECKSUM_VERIFIED"
                    ));
                }
                return findings;
            }
        };
    }

    /**
     * SG008: Proposed schema contradicts a declared service contract.
     */
    @Bean
    public SchemaGuardRule sg008BreakingServiceContractRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG008"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    if (change.changeType() == ChangeType.COLUMN_DROPPED
                            || change.changeType() == ChangeType.TABLE_DROPPED
                            || change.changeType() == ChangeType.COLUMN_TYPE_CHANGED) {

                        String obj = change.fullyQualifiedObject();
                        List<String> consumers = ctx.getConsumersFor(obj);
                        if (!consumers.isEmpty()) {
                            List<String> evidence = new ArrayList<>();
                            evidence.add("Candidate schema modifies or removes " + obj + " (" + change.changeType() + ")");
                            consumers.forEach(service -> evidence.add(service + " declares a read dependency on " + obj));

                            findings.add(new Finding(
                                    id(),
                                    Severity.CRITICAL,
                                    "Breaking database contract",
                                    "Proposed migration breaks declared service contract on " + obj,
                                    obj,
                                    new SourceLocation(change.sourceFile(), change.sourceLine()),
                                    evidence,
                                    RemediationTemplates.expandMigrateContractForColumn(obj),
                                    "CONFIRMED_CONTRACT_BREAK"
                            ));
                        }
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG009: Destructive data update/delete without declared backup or recovery strategy.
     */
    @Bean
    public SchemaGuardRule sg009DestructiveDmlRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG009"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.backupStrategyDeclared()) return findings;

                for (NormalizedOperation op : ctx.allOperations()) {
                    boolean destructiveDml = Boolean.TRUE.equals(op.arguments().get("destructiveDml"));
                    if (destructiveDml) {
                        String obj = op.schemaName() + "." + op.objectName();
                        findings.add(new Finding(
                                id(),
                                Severity.REVIEW_REQUIRED,
                                "Destructive data modification without declared backup strategy",
                                "Migration executes destructive DML on " + obj + " without a declared backup/recovery strategy",
                                obj,
                                new SourceLocation(op.sourceFile(), op.startLine()),
                                List.of("Detected destructive data operation on " + obj, "No backup strategy declared in context"),
                                "Document a verified backup/restore strategy or move data cleanup to an auditable batch job.",
                                "STATIC_DML_INSPECTION"
                        ));
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG010: SQL statement or expression lies outside supported analysis scope.
     */
    @Bean
    public SchemaGuardRule sg010UnsupportedSqlScopeRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG010"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                for (NormalizedOperation op : ctx.allOperations()) {
                    if (op.parserStatus() == ParserStatus.UNSUPPORTED) {
                        String reason = String.valueOf(op.arguments().getOrDefault("reason", "Statement outside supported PostgreSQL subset"));
                        String obj = op.schemaName() + "." + op.objectName();
                        findings.add(new Finding(
                                id(),
                                Severity.REVIEW_REQUIRED,
                                "SQL statement outside supported analysis scope",
                                "SchemaGuard cannot safely guarantee analysis for unsupported statement in " + op.sourceFile(),
                                obj,
                                new SourceLocation(op.sourceFile(), op.startLine()),
                                List.of("Parser status: UNSUPPORTED", "Reason: " + reason),
                                "Request manual DBA review; unsupported syntax is never silently approved.",
                                "INCOMPLETE_ANALYSIS_COVERAGE"
                        ));
                    }
                }
                return findings;
            }
        };
    }

    /**
     * SG011: Observed schema differs from expected migration-derived schema.
     */
    @Bean
    public SchemaGuardRule sg011SchemaDriftRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG011"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.observedDriftDetected()) {
                    findings.add(new Finding(
                            id(),
                            Severity.HIGH,
                            "Environment schema drift detected",
                            "Observed schema differs from migration-derived baseline schema",
                            "database.schema_drift",
                            new SourceLocation("observed-environment", 1),
                            ctx.driftEvidence() != null && !ctx.driftEvidence().isEmpty()
                                    ? ctx.driftEvidence()
                                    : List.of("Canonical hash mismatch between expected baseline and observed schema"),
                            "Reconcile out-of-band schema changes via tracked migrations before deploying new changes.",
                            "SNAPSHOT_HASH_COMPARISON"
                    ));
                }
                return findings;
            }
        };
    }

    /**
     * SG012: Type alteration or other operation may rewrite a table or impose a long lock.
     */
    @Bean
    public SchemaGuardRule sg012TableRewriteLockRule() {
        return new SchemaGuardRule() {
            @Override
            public String id() { return "SG012"; }

            @Override
            public List<Finding> evaluate(AnalysisContext ctx) {
                List<Finding> findings = new ArrayList<>();
                if (ctx.diffResult() == null) return findings;

                for (SchemaChange change : ctx.diffResult().changes()) {
                    if (change.changeType() == ChangeType.COLUMN_TYPE_CHANGED) {
                        TableProfile profile = ctx.getTableProfile(change.schemaName(), change.tableName());
                        if (profile.isLargeOrUnknownOrCritical()) {
                            String obj = change.fullyQualifiedObject();
                            findings.add(new Finding(
                                    id(),
                                    Severity.HIGH,
                                    "Operation may rewrite table and acquire ACCESS EXCLUSIVE lock",
                                    "Altering column type on " + obj + " may trigger a full table rewrite in PostgreSQL",
                                    obj,
                                    new SourceLocation(change.sourceFile(), change.sourceLine()),
                                    List.of(
                                            "Type alteration: " + change.beforeValue() + " -> " + change.afterValue(),
                                            "Table profile size: " + profile.sizeClass() + ", critical: " + profile.productionCritical()
                                    ),
                                    RemediationTemplates.expandMigrateContractForColumn(obj),
                                    "OPERATIONAL_RISK_ANALYSIS"
                            ));
                        }
                    }
                }
                return findings;
            }
        };
    }
}