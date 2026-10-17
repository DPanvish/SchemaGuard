package com.schemaguard.core.diff;

import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.parser.OperationType;
import com.schemaguard.core.schema.ColumnModel;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableModel;
import com.schemaguard.core.schema.TableObjects.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SchemaDiffEngine {

    private static final Logger log = LoggerFactory.getLogger(SchemaDiffEngine.class);

    public SchemaDiffResult compare(
            SchemaSnapshot baseSnapshot,
            SchemaSnapshot candidateSnapshot,
            List<NormalizedOperation> candidateOperations) {

        if (!Objects.equals(baseSnapshot.schemaModelVersion(), candidateSnapshot.schemaModelVersion())) {
            throw new IllegalArgumentException("Cannot compare snapshots with different schema model versions");
        }

        log.info("Starting schema comparison [baseHash={}, candidateHash={}]",
                baseSnapshot.canonicalHash(), candidateSnapshot.canonicalHash());

        List<NormalizedOperation> ops = candidateOperations == null ? List.of() : candidateOperations;
        List<SchemaChange> changes = new ArrayList<>();

        Map<String, SchemaNamespace> baseSchemas = toMap(baseSnapshot.schemas(), SchemaNamespace::name);
        Map<String, SchemaNamespace> candSchemas = toMap(candidateSnapshot.schemas(), SchemaNamespace::name);

        Set<String> allSchemaNames = new TreeSet<>();
        allSchemaNames.addAll(baseSchemas.keySet());
        allSchemaNames.addAll(candSchemas.keySet());

        for (String schemaName : allSchemaNames) {
            SchemaNamespace baseNs = baseSchemas.getOrDefault(schemaName,
                    new SchemaNamespace(schemaName, List.of(), List.of(), List.of()));
            SchemaNamespace candNs = candSchemas.getOrDefault(schemaName,
                    new SchemaNamespace(schemaName, List.of(), List.of(), List.of()));

            diffTables(schemaName, baseNs.tables(), candNs.tables(), ops, changes);
            diffViews(schemaName, baseNs.views(), candNs.views(), ops, changes);
        }

        int unmappedCount = (int) changes.stream().filter(c -> !c.mappedToSource()).count();
        boolean identical = baseSnapshot.canonicalHash().equals(candidateSnapshot.canonicalHash()) && changes.isEmpty();

        log.info("Completed schema comparison: {} change(s) detected, {} unmapped change(s)",
                changes.size(), unmappedCount);

        return new SchemaDiffResult(
                baseSnapshot.canonicalHash(),
                candidateSnapshot.canonicalHash(),
                identical,
                Collections.unmodifiableList(changes),
                unmappedCount
        );
    }

    private void diffTables(
            String schemaName,
            List<TableModel> baseTables,
            List<TableModel> candTables,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, TableModel> baseMap = toMap(baseTables, TableModel::name);
        Map<String, TableModel> candMap = toMap(candTables, TableModel::name);

        Set<String> allTables = new TreeSet<>();
        allTables.addAll(baseMap.keySet());
        allTables.addAll(candMap.keySet());

        for (String tableName : allTables) {
            TableModel baseTable = baseMap.get(tableName);
            TableModel candTable = candMap.get(tableName);

            if (baseTable == null && candTable != null) {
                Optional<NormalizedOperation> match = findOp(ops, OperationType.CREATE_TABLE, schemaName, tableName, null);
                changes.add(buildChange(ChangeType.TABLE_ADDED, schemaName, tableName, tableName,
                        null, "TABLE", match, "Table created with " + candTable.columns().size() + " column(s)"));
            } else if (baseTable != null && candTable == null) {
                Optional<NormalizedOperation> match = findOp(ops, OperationType.DROP_TABLE, schemaName, tableName, null);
                changes.add(buildChange(ChangeType.TABLE_DROPPED, schemaName, tableName, tableName,
                        "TABLE", null, match, "Table dropped"));
            } else if (baseTable != null) {
                diffColumns(schemaName, tableName, baseTable.columns(), candTable.columns(), ops, changes);
                diffPrimaryKey(schemaName, tableName, baseTable.primaryKey(), candTable.primaryKey(), ops, changes);
                diffUniqueConstraints(schemaName, tableName, baseTable.uniqueConstraints(), candTable.uniqueConstraints(), ops, changes);
                diffForeignKeys(schemaName, tableName, baseTable.foreignKeys(), candTable.foreignKeys(), ops, changes);
                diffCheckConstraints(schemaName, tableName, baseTable.checkConstraints(), candTable.checkConstraints(), ops, changes);
                diffIndexes(schemaName, tableName, baseTable.indexes(), candTable.indexes(), ops, changes);
            }
        }
    }

    private void diffColumns(
            String schemaName,
            String tableName,
            List<ColumnModel> baseCols,
            List<ColumnModel> candCols,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, ColumnModel> baseMap = toMap(baseCols, ColumnModel::name);
        Map<String, ColumnModel> candMap = toMap(candCols, ColumnModel::name);

        List<ColumnModel> addedCols = new ArrayList<>();
        List<ColumnModel> droppedCols = new ArrayList<>();

        Set<String> allCols = new TreeSet<>();
        allCols.addAll(baseMap.keySet());
        allCols.addAll(candMap.keySet());

        for (String colName : allCols) {
            ColumnModel baseCol = baseMap.get(colName);
            ColumnModel candCol = candMap.get(colName);

            if (baseCol == null && candCol != null) {
                addedCols.add(candCol);
                Optional<NormalizedOperation> match = findOp(ops, OperationType.ADD_COLUMN, schemaName, tableName, colName);
                changes.add(buildChange(ChangeType.COLUMN_ADDED, schemaName, tableName, colName,
                        null, candCol.type() + (candCol.nullable() ? " NULL" : " NOT NULL"),
                        match, "Added column " + colName));
            } else if (baseCol != null && candCol == null) {
                droppedCols.add(baseCol);
                Optional<NormalizedOperation> match = findOp(ops, OperationType.DROP_COLUMN, schemaName, tableName, colName);
                changes.add(buildChange(ChangeType.COLUMN_DROPPED, schemaName, tableName, colName,
                        baseCol.type(), null, match, "Dropped column " + colName));
            } else if (baseCol != null) {
                if (!Objects.equals(baseCol.type(), candCol.type())) {
                    Optional<NormalizedOperation> match = findOp(ops, OperationType.ALTER_COLUMN_TYPE, schemaName, tableName, colName);
                    changes.add(buildChange(ChangeType.COLUMN_TYPE_CHANGED, schemaName, tableName, colName,
                            baseCol.type(), candCol.type(), match,
                            "Column type changed from " + baseCol.type() + " to " + candCol.type()));
                }
                if (baseCol.nullable() != candCol.nullable()) {
                    Optional<NormalizedOperation> match = findOp(ops, OperationType.ALTER_COLUMN_TYPE, schemaName, tableName, colName);
                    changes.add(buildChange(ChangeType.COLUMN_NULLABILITY_CHANGED, schemaName, tableName, colName,
                            baseCol.nullable() ? "NULLABLE" : "NOT NULL",
                            candCol.nullable() ? "NULLABLE" : "NOT NULL",
                            match, "Column nullability changed"));
                }
                if (!Objects.equals(baseCol.defaultExpression(), candCol.defaultExpression())) {
                    Optional<NormalizedOperation> match = findOp(ops, OperationType.ALTER_COLUMN_TYPE, schemaName, tableName, colName);
                    changes.add(buildChange(ChangeType.COLUMN_DEFAULT_CHANGED, schemaName, tableName, colName,
                            baseCol.defaultExpression(), candCol.defaultExpression(),
                            match, "Column default expression changed"));
                }
            }
        }

        // Step 5 of Diff Algorithm: Propose rename candidates conservatively without assuming certainty
        for (ColumnModel dropped : droppedCols) {
            for (ColumnModel added : addedCols) {
                if (Objects.equals(dropped.type(), added.type())) {
                    Optional<NormalizedOperation> match = findOp(ops, OperationType.DROP_COLUMN, schemaName, tableName, dropped.name());
                    changes.add(buildChange(
                            ChangeType.RENAME_CANDIDATE,
                            schemaName,
                            tableName,
                            dropped.name() + " -> " + added.name(),
                            dropped.name(),
                            added.name(),
                            match,
                            "Possible column rename candidate (" + dropped.type() + "); not inferred as certain from snapshots alone"
                    ));
                }
            }
        }
    }

    private void diffPrimaryKey(
            String schemaName,
            String tableName,
            PrimaryKeyModel basePk,
            PrimaryKeyModel candPk,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        if (!Objects.equals(basePk, candPk)) {
            String before = basePk == null ? null : basePk.name() + "(" + String.join(",", basePk.columns()) + ")";
            String after = candPk == null ? null : candPk.name() + "(" + String.join(",", candPk.columns()) + ")";
            Optional<NormalizedOperation> match = findOp(ops, OperationType.ADD_CONSTRAINT, schemaName, tableName, null);
            changes.add(buildChange(ChangeType.PRIMARY_KEY_CHANGED, schemaName, tableName,
                    candPk != null ? candPk.name() : (basePk != null ? basePk.name() : "pk"),
                    before, after, match, "Primary key definition changed"));
        }
    }

    private void diffUniqueConstraints(
            String schemaName,
            String tableName,
            List<UniqueConstraintModel> baseUniques,
            List<UniqueConstraintModel> candUniques,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, UniqueConstraintModel> baseMap = toMap(baseUniques, UniqueConstraintModel::name);
        Map<String, UniqueConstraintModel> candMap = toMap(candUniques, UniqueConstraintModel::name);

        for (String name : candMap.keySet()) {
            if (!baseMap.containsKey(name)) {
                Optional<NormalizedOperation> match = findOp(ops, OperationType.ADD_CONSTRAINT, schemaName, tableName, null);
                changes.add(buildChange(ChangeType.UNIQUE_CONSTRAINT_ADDED, schemaName, tableName, name,
                        null, String.join(",", candMap.get(name).columns()), match, "Unique constraint added"));
            }
        }
        for (String name : baseMap.keySet()) {
            if (!candMap.containsKey(name)) {
                changes.add(buildChange(ChangeType.UNIQUE_CONSTRAINT_DROPPED, schemaName, tableName, name,
                        String.join(",", baseMap.get(name).columns()), null, Optional.empty(), "Unique constraint dropped"));
            }
        }
    }

    private void diffForeignKeys(
            String schemaName,
            String tableName,
            List<ForeignKeyModel> baseFks,
            List<ForeignKeyModel> candFks,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, ForeignKeyModel> baseMap = toMap(baseFks, ForeignKeyModel::name);
        Map<String, ForeignKeyModel> candMap = toMap(candFks, ForeignKeyModel::name);

        for (String name : candMap.keySet()) {
            if (!baseMap.containsKey(name)) {
                ForeignKeyModel fk = candMap.get(name);
                Optional<NormalizedOperation> match = findOp(ops, OperationType.ADD_CONSTRAINT, schemaName, tableName, null);
                changes.add(buildChange(ChangeType.FOREIGN_KEY_ADDED, schemaName, tableName, name,
                        null, fk.referencedSchema() + "." + fk.referencedTable(), match, "Foreign key added"));
            }
        }
        for (String name : baseMap.keySet()) {
            if (!candMap.containsKey(name)) {
                ForeignKeyModel fk = baseMap.get(name);
                changes.add(buildChange(ChangeType.FOREIGN_KEY_DROPPED, schemaName, tableName, name,
                        fk.referencedSchema() + "." + fk.referencedTable(), null, Optional.empty(), "Foreign key dropped"));
            }
        }
    }

    private void diffCheckConstraints(
            String schemaName,
            String tableName,
            List<CheckConstraintModel> baseChecks,
            List<CheckConstraintModel> candChecks,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, CheckConstraintModel> baseMap = toMap(baseChecks, CheckConstraintModel::name);
        Map<String, CheckConstraintModel> candMap = toMap(candChecks, CheckConstraintModel::name);

        for (String name : candMap.keySet()) {
            if (!baseMap.containsKey(name)) {
                Optional<NormalizedOperation> match = findOp(ops, OperationType.ADD_CONSTRAINT, schemaName, tableName, null);
                changes.add(buildChange(ChangeType.CHECK_CONSTRAINT_ADDED, schemaName, tableName, name,
                        null, candMap.get(name).expression(), match, "Check constraint added"));
            }
        }
        for (String name : baseMap.keySet()) {
            if (!candMap.containsKey(name)) {
                changes.add(buildChange(ChangeType.CHECK_CONSTRAINT_DROPPED, schemaName, tableName, name,
                        baseMap.get(name).expression(), null, Optional.empty(), "Check constraint dropped"));
            }
        }
    }

    private void diffIndexes(
            String schemaName,
            String tableName,
            List<IndexModel> baseIndexes,
            List<IndexModel> candIndexes,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, IndexModel> baseMap = toMap(baseIndexes, IndexModel::name);
        Map<String, IndexModel> candMap = toMap(candIndexes, IndexModel::name);

        for (String name : candMap.keySet()) {
            if (!baseMap.containsKey(name)) {
                Optional<NormalizedOperation> match = findOp(ops, OperationType.ADD_INDEX, schemaName, tableName, null);
                changes.add(buildChange(ChangeType.INDEX_ADDED, schemaName, tableName, name,
                        null, String.join(",", candMap.get(name).columns()), match, "Index added"));
            }
        }
        for (String name : baseMap.keySet()) {
            if (!candMap.containsKey(name)) {
                changes.add(buildChange(ChangeType.INDEX_DROPPED, schemaName, tableName, name,
                        String.join(",", baseMap.get(name).columns()), null, Optional.empty(), "Index dropped"));
            }
        }
    }

    private void diffViews(
            String schemaName,
            List<ViewModel> baseViews,
            List<ViewModel> candViews,
            List<NormalizedOperation> ops,
            List<SchemaChange> changes) {

        Map<String, ViewModel> baseMap = toMap(baseViews, ViewModel::name);
        Map<String, ViewModel> candMap = toMap(candViews, ViewModel::name);

        for (String name : candMap.keySet()) {
            if (!baseMap.containsKey(name)) {
                changes.add(buildChange(ChangeType.VIEW_ADDED, schemaName, name, name,
                        null, candMap.get(name).normalizedDefinition(), Optional.empty(), "View added"));
            } else {
                ViewModel b = baseMap.get(name);
                ViewModel c = candMap.get(name);
                if (!Objects.equals(b.normalizedDefinition(), c.normalizedDefinition())) {
                    changes.add(buildChange(ChangeType.VIEW_DEFINITION_CHANGED, schemaName, name, name,
                            b.normalizedDefinition(), c.normalizedDefinition(), Optional.empty(), "View definition modified"));
                }
            }
        }
        for (String name : baseMap.keySet()) {
            if (!candMap.containsKey(name)) {
                changes.add(buildChange(ChangeType.VIEW_DROPPED, schemaName, name, name,
                        baseMap.get(name).normalizedDefinition(), null, Optional.empty(), "View dropped"));
            }
        }
    }

    private Optional<NormalizedOperation> findOp(
            List<NormalizedOperation> ops,
            OperationType type,
            String schemaName,
            String tableName,
            String columnName) {

        return ops.stream()
                .filter(op -> op.operationType() == type)
                .filter(op -> schemaName.equalsIgnoreCase(op.schemaName()))
                .filter(op -> tableName.equalsIgnoreCase(op.objectName()))
                .filter(op -> columnName == null || columnName.equalsIgnoreCase(op.columnName()))
                .findFirst();
    }

    private SchemaChange buildChange(
            ChangeType changeType,
            String schemaName,
            String tableName,
            String objectName,
            String beforeValue,
            String afterValue,
            Optional<NormalizedOperation> matchedOp,
            String details) {

        if (matchedOp.isPresent()) {
            NormalizedOperation op = matchedOp.get();
            return new SchemaChange(
                    changeType, schemaName, tableName, objectName,
                    beforeValue, afterValue, op.sourceFile(), op.startLine(), true, details
            );
        }
        return new SchemaChange(
                changeType, schemaName, tableName, objectName,
                beforeValue, afterValue, null, null, false, details
        );
    }

    private <T> Map<String, T> toMap(List<T> items, Function<T, String> keyMapper) {
        if (items == null) {
            return Collections.emptyMap();
        }
        return items.stream().collect(Collectors.toMap(
                keyMapper,
                Function.identity(),
                (existing, replacement) -> existing,
                LinkedHashMap::new
        ));
    }
}