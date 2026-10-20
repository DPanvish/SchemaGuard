package com.schemaguard.core.dependencies;

import com.schemaguard.core.dependencies.DependencyGraph.DependencyEdge;
import com.schemaguard.core.dependencies.DependencyGraph.EdgeType;
import com.schemaguard.core.dependencies.ServiceContractManifest.ServiceDefinition;
import com.schemaguard.core.dependencies.ServiceContractManifest.TableReadContract;
import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableModel;
import com.schemaguard.core.schema.TableObjects.ForeignKeyModel;
import com.schemaguard.core.schema.TableObjects.ViewModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DependencyGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DependencyGraphBuilder.class);

    public DependencyGraph build(
            SchemaSnapshot baseSnapshot,
            ServiceContractManifest contractManifest,
            List<NormalizedOperation> candidateOperations) {

        List<DependencyEdge> edges = new ArrayList<>();
        Set<String> contractedTables = new TreeSet<>();
        int totalBaseTables = 0;

        // 1. Register Service Contract Edges (SERVICE_READS_TABLE & SERVICE_READS_COLUMN)
        if (contractManifest != null && contractManifest.services() != null) {
            for (Map.Entry<String, ServiceDefinition> entry : contractManifest.services().entrySet()) {
                String serviceName = entry.getKey().trim();
                for (TableReadContract read : entry.getValue().reads()) {
                    String qualifiedTable = normalize(read.table());
                    contractedTables.add(qualifiedTable);

                    edges.add(new DependencyEdge(
                            serviceName,
                            qualifiedTable,
                            EdgeType.SERVICE_READS_TABLE,
                            serviceName + " declares a read dependency on table " + qualifiedTable
                    ));

                    for (String col : read.columns()) {
                        String qualifiedCol = qualifiedTable + "." + normalize(col);
                        edges.add(new DependencyEdge(
                                serviceName,
                                qualifiedCol,
                                EdgeType.SERVICE_READS_COLUMN,
                                serviceName + " declares a read dependency on column " + qualifiedCol
                        ));
                    }
                }
            }
        }

        // 2. Register Database Catalog Edges (FOREIGN_KEY_REFERENCES_TABLE & VIEW_DEPENDS_ON_OBJECT)
        if (baseSnapshot != null && baseSnapshot.schemas() != null) {
            for (SchemaNamespace ns : baseSnapshot.schemas()) {
                String schemaName = normalize(ns.name());
                List<TableModel> tables = ns.tables() == null ? List.of() : ns.tables();
                totalBaseTables += tables.size();

                for (TableModel table : tables) {
                    String sourceTable = schemaName + "." + normalize(table.name());
                    if (table.foreignKeys() != null) {
                        for (ForeignKeyModel fk : table.foreignKeys()) {
                            String targetTable = normalize(fk.referencedSchema()) + "." + normalize(fk.referencedTable());
                            edges.add(new DependencyEdge(
                                    sourceTable,
                                    targetTable,
                                    EdgeType.FOREIGN_KEY_REFERENCES_TABLE,
                                    "Foreign key " + fk.name() + " on " + sourceTable + " references " + targetTable
                            ));
                        }
                    }
                }

                if (ns.views() != null) {
                    for (ViewModel view : ns.views()) {
                        String viewName = schemaName + "." + normalize(view.name());
                        String defLower = view.normalizedDefinition() != null
                                ? view.normalizedDefinition().toLowerCase(Locale.ROOT) : "";

                        for (TableModel table : tables) {
                            String tableName = normalize(table.name());
                            if (defLower.contains(tableName)) {
                                String targetTable = schemaName + "." + tableName;
                                edges.add(new DependencyEdge(
                                        viewName,
                                        targetTable,
                                        EdgeType.VIEW_DEPENDS_ON_OBJECT,
                                        "View " + viewName + " references table " + targetTable
                                ));
                            }
                        }
                    }
                }
            }
        }

        // 3. Register Migration Change Edges (MIGRATION_CHANGES_OBJECT)
        if (candidateOperations != null) {
            for (NormalizedOperation op : candidateOperations) {
                String target = normalize(op.schemaName()) + "." + normalize(op.objectName());
                if (op.columnName() != null && !op.columnName().isBlank()) {
                    target = target + "." + normalize(op.columnName());
                }
                edges.add(new DependencyEdge(
                        op.sourceFile() + ":" + op.startLine(),
                        target,
                        EdgeType.MIGRATION_CHANGES_OBJECT,
                        "Migration " + op.sourceFile() + " performs " + op.operationType() + " on " + target
                ));
            }
        }

        log.info("Built dependency graph with {} directed edge(s); contract coverage covers {}/{} base table(s)",
                edges.size(), contractedTables.size(), totalBaseTables);

        return new DependencyGraph(edges, contractedTables, totalBaseTables);
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}