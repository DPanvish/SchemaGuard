package com.schemaguard.core.dependencies;

import java.util.*;

public final class DependencyGraph {

    public enum EdgeType {
        SERVICE_READS_TABLE,
        SERVICE_READS_COLUMN,
        VIEW_DEPENDS_ON_OBJECT,
        FOREIGN_KEY_REFERENCES_TABLE,
        MIGRATION_CHANGES_OBJECT
    }

    public record DependencyEdge(
        String sourceNode,
        String targetObject,
        EdgeType edgeType,
        String evidence
    ) {}

    public record ImpactAssessment(
        String changedObject,
        List<String> affectedServices,
        List<String> affectedDatabaseObjects,
        List<String> supportingEvidence,
        boolean hasContractCoverage
    ) {}

    private final List<DependencyEdge> edges;
    private final Map<String, List<DependencyEdge>> reverseEdgesByTarget;
    private final Set<String> contractedTables;
    private final int totalBaseTables;

    public DependencyGraph(List<DependencyEdge> edges, Set<String> contractedTables, int totalBaseTables) {
        this.edges = List.copyOf(edges);
        this.contractedTables = Set.copyOf(contractedTables);
        this.totalBaseTables = totalBaseTables;

        Map<String, List<DependencyEdge>> reverseMap = new HashMap<>();
        for (DependencyEdge edge : edges) {
            String key = normalize(edge.targetObject());
            reverseMap.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }
        this.reverseEdgesByTarget = Collections.unmodifiableMap(reverseMap);
    }

    public List<DependencyEdge> edges() {
        return edges;
    }

    /**
     * Traverses reverse edges for a changed table or column to identify all impacted consumers and objects.
     */
    public ImpactAssessment assessImpact(String fullyQualifiedObject) {
        String normalizedObj = normalize(fullyQualifiedObject);
        String tableKey = extractTableQualifiedName(normalizedObj);

        List<DependencyEdge> directHits = new ArrayList<>(
                reverseEdgesByTarget.getOrDefault(normalizedObj, List.of())
        );

        // If a table is dropped/altered, also include column-level consumers of that table
        if (normalizedObj.equals(tableKey)) {
            for (Map.Entry<String, List<DependencyEdge>> entry : reverseEdgesByTarget.entrySet()) {
                if (entry.getKey().startsWith(tableKey + ".")) {
                    directHits.addAll(entry.getValue());
                }
            }
        }

        Set<String> services = new TreeSet<>();
        Set<String> dbObjects = new TreeSet<>();
        List<String> evidence = new ArrayList<>();

        for (DependencyEdge edge : directHits) {
            if (edge.edgeType() == EdgeType.SERVICE_READS_COLUMN || edge.edgeType() == EdgeType.SERVICE_READS_TABLE) {
                services.add(edge.sourceNode());
                evidence.add(edge.evidence());
            } else if (edge.edgeType() == EdgeType.VIEW_DEPENDS_ON_OBJECT
                    || edge.edgeType() == EdgeType.FOREIGN_KEY_REFERENCES_TABLE) {
                dbObjects.add(edge.sourceNode());
                evidence.add(edge.evidence());
            }
        }

        boolean tableCovered = contractedTables.contains(tableKey);
        if (!tableCovered) {
            evidence.add("Coverage gap: no service contract is registered for table " + tableKey
                    + " (missing contract is not proof that no runtime consumer exists)");
        }

        return new ImpactAssessment(
                normalizedObj,
                List.copyOf(services),
                List.copyOf(dbObjects),
                Collections.unmodifiableList(evidence),
                tableCovered
        );
    }

    /**
     * Produces the lookup map required by AnalysisContext (object -> list of declared service names).
     */
    public Map<String, List<String>> toDeclaredConsumersMap() {
        Map<String, Set<String>> grouped = new TreeMap<>();
        for (DependencyEdge edge : edges) {
            if (edge.edgeType() == EdgeType.SERVICE_READS_COLUMN || edge.edgeType() == EdgeType.SERVICE_READS_TABLE) {
                grouped.computeIfAbsent(normalize(edge.targetObject()), k -> new TreeSet<>())
                        .add(edge.sourceNode());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((k, v) -> result.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(result);
    }

    public double tableContractCoverageRatio() {
        if (totalBaseTables == 0) {
            return 1.0;
        }
        return (double) contractedTables.size() / totalBaseTables;
    }

    public boolean isTableCovered(String schemaName, String tableName) {
        return contractedTables.contains(normalize(schemaName + "." + tableName));
    }

    private String extractTableQualifiedName(String fqObj) {
        String[] parts = fqObj.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return fqObj;
    }

    private String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}