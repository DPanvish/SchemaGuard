package com.schemaguard.core.rules;

import com.schemaguard.core.diff.SchemaDiffResult;
import com.schemaguard.core.parser.MigrationArtifact;
import com.schemaguard.core.parser.MigrationHistoryValidator.TamperedMigration;
import com.schemaguard.core. parser.NormalizedOperation;
import com.schemaguard.core.schema.SchemaSnapshot;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record AnalysisContext(
    SchemaSnapshot baseSnapshot,
    SchemaSnapshot candidateSnapshot,
    SchemaDiffResult diffResult,
    List<MigrationArtifact> candidateArtifacts,
    List<TamperedMigration> tamperedMigrations,
    Map<String, List<String>> declaredConsumersByObject, // e.g., "public.customers.email" -> ["billing-api", "notification-worker"]
    Map<String, TableProfile> tableProfiles,
    boolean observedDriftDetected,
    List<String> driftEvidence,
    boolean backupStrategyDeclared
) {
    public List<NormalizedOperation> allOperations() {
        if (candidateArtifacts == null) {
            return List.of();
        }
        return candidateArtifacts.stream()
                .flatMap(a -> a.operations().stream())
                .toList();
    }

    public List<String> getConsumersFor(String fullyQualifiedObject) {
        if (declaredConsumersByObject == null || fullyQualifiedObject == null) {
            return List.of();
        }
        return declaredConsumersByObject.getOrDefault(
                fullyQualifiedObject.toLowerCase(Locale.ROOT),
                Collections.emptyList()
        );
    }

    public TableProfile getTableProfile(String schemaName, String tableName) {
        String key = (schemaName + "." + tableName).toLowerCase(Locale.ROOT);
        if (tableProfiles == null || !tableProfiles.containsKey(key)) {
            return TableProfile.unknown(key);
        }
        return tableProfiles.get(key);
    }

    public Set<String> allDeclaredObjects() {
        return declaredConsumersByObject == null ? Set.of() : declaredConsumersByObject.keySet();
    }
}