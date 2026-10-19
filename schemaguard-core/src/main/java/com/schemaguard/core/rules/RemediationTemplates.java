package com.schemaguard.core.rules;

public final class RemediationTemplates {

    private RemediationTemplates() {}

    public static final String UNVERIFIABLE_PRECONDITIONS_NOTICE =
            "Note (Unverifiable Preconditions): SchemaGuard cannot automatically verify undeclared runtime SQL consumers, " +
            "ad-hoc BI queries, or whether older application deployment versions are still active in production.";

    public static String expandMigrateContractForColumn(String objectRef) {
        return """
                Use an Expand-Migrate-Verify-Contract rollout for %s:
                1. EXPAND: Add the replacement column/structure as NULLABLE while keeping %s intact so existing services continue to run.
                2. MIGRATE: Update application writers to dual-write and backfill existing rows in batches.
                3. VERIFY: Confirm data completeness and verify all declared services read from the new structure.
                4. CONTRACT: Remove %s in a separate follow-up migration after all old consumer deployments are retired.
                %s""".formatted(objectRef, objectRef, objectRef, UNVERIFIABLE_PRECONDITIONS_NOTICE);
    }

    public static String safeNotNullRollout(String objectRef) {
        return """
                Avoid adding an immediate NOT NULL or table-rewriting default on %s:
                1. EXPAND: Add the column as NULLABLE (or add a CHECK (col IS NOT NULL) NOT VALID constraint).
                2. MIGRATE: Backfill existing NULL rows in batches and update application writers.
                3. VERIFY: Confirm zero NULL rows remain in production and run VALIDATE CONSTRAINT.
                4. CONTRACT: Promote to SET NOT NULL once validation succeeds.
                """.formatted(objectRef).trim();
    }

    public static String concurrentIndexCreation(String objectRef) {
        return "Use CREATE INDEX CONCURRENTLY on " + objectRef +
               " outside a transaction block to avoid blocking concurrent writes (PostgreSQL CREATE INDEX documentation).";
    }
}