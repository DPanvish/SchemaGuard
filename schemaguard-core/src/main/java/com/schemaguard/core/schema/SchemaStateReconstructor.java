package com.schemaguard.core.schema;

import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableObjects.IndexModel;
import com.schemaguard.core.schema.TableObjects.PrimaryKeyModel;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

@Service
public class SchemaStateReconstructor {

    private static final Logger log = LoggerFactory.getLogger(SchemaStateReconstructor.class);

    private final CanonicalSchemaNormalizer normalizer;
    private final PostgresCatalogSnapshotter catalogSnapshotter;

    public SchemaStateReconstructor(
            CanonicalSchemaNormalizer normalizer,
            PostgresCatalogSnapshotter catalogSnapshotter) {
        this.normalizer = normalizer;
        this.catalogSnapshotter = catalogSnapshotter;
    }

    /**
     * Reconstructs a canonical SchemaSnapshot from an ordered list of migration files.
     * Uses isolated Testcontainers PostgreSQL when useDockerSandbox is true, or deterministic
     * DDL state reconstruction for fast local/CI checks.
     */
    public SchemaSnapshot reconstruct(List<File> orderedMigrations, boolean useDockerSandbox) {
        if (useDockerSandbox) {
            log.info("Reconstructing schema in isolated Docker PostgreSQL container ({} files)", orderedMigrations.size());
            return catalogSnapshotter.replayAndCaptureInSandbox(orderedMigrations);
        }
        return reconstructFromDdlChain(orderedMigrations);
    }

    public SchemaSnapshot reconstructFromDdlChain(List<File> orderedMigrations) {
        log.debug("Reconstructing canonical schema state from {} SQL migration file(s)", orderedMigrations.size());
        Map<String, MutableTable> tablesByQualifiedName = new LinkedHashMap<>();

        for (File file : orderedMigrations) {
            try {
                String sql = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                Statements statements = CCJSqlParserUtil.parseStatements(sql);
                for (Statement stmt : statements.getStatements()) {
                    applyStatementToState(stmt, tablesByQualifiedName);
                }
            } catch (Exception e) {
                log.warn("Skipping state application for unparseable statement in {}: {}", file.getName(), e.getMessage());
            }
        }

        Map<String, List<TableModel>> tablesBySchema = new TreeMap<>();
        for (MutableTable mt : tablesByQualifiedName.values()) {
            tablesBySchema.computeIfAbsent(mt.schemaName, k -> new ArrayList<>()).add(mt.toTableModel());
        }

        if (tablesBySchema.isEmpty()) {
            tablesBySchema.put("public", List.of());
        }

        List<SchemaNamespace> namespaces = tablesBySchema.entrySet().stream()
                .map(e -> new SchemaNamespace(e.getKey(), e.getValue(), List.of(), List.of()))
                .toList();

        return normalizer.normalizeAndHash("16.x-canonical", namespaces);
    }

    private void applyStatementToState(Statement stmt, Map<String, MutableTable> tables) {
        if (stmt instanceof CreateTable ct) {
            String schema = cleanSchema(ct.getTable().getSchemaName());
            String table = cleanId(ct.getTable().getName());
            MutableTable mt = new MutableTable(schema, table);

            if (ct.getColumnDefinitions() != null) {
                List<String> pkCols = new ArrayList<>();
                for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                    String colName = cleanId(cd.getColumnName());
                    String colType = cd.getColDataType() != null ? cd.getColDataType().toString() : "text";
                    List<String> specs = cd.getColumnSpecs() != null ? cd.getColumnSpecs() : List.of();
                    String joinedSpecs = String.join(" ", specs).toUpperCase(Locale.ROOT);

                    boolean isPk = joinedSpecs.contains("PRIMARY KEY");
                    boolean isNotNull = isPk || joinedSpecs.contains("NOT NULL");
                    if (isPk) {
                        pkCols.add(colName);
                    }
                    mt.columns.put(colName, new ColumnModel(colName, colType, !isNotNull, null, false));
                }
                if (!pkCols.isEmpty()) {
                    mt.primaryKey = new PrimaryKeyModel(table + "_pkey", pkCols);
                }
            }
            tables.put(schema + "." + table, mt);

        } else if (stmt instanceof Drop drop && "TABLE".equalsIgnoreCase(drop.getType())) {
            String schema = cleanSchema(drop.getName().getSchemaName());
            String table = cleanId(drop.getName().getName());
            tables.remove(schema + "." + table);

        } else if (stmt instanceof Alter alter) {
            String schema = cleanSchema(alter.getTable().getSchemaName());
            String table = cleanId(alter.getTable().getName());
            MutableTable mt = tables.get(schema + "." + table);
            if (mt == null || alter.getAlterExpressions() == null) {
                return;
            }
            for (AlterExpression expr : alter.getAlterExpressions()) {
                AlterOperation op = expr.getOperation();
                if (op == AlterOperation.ADD && expr.getColDataTypeList() != null) {
                    for (AlterExpression.ColumnDataType cdt : expr.getColDataTypeList()) {
                        String colName = cleanId(cdt.getColumnName());
                        String colType = cdt.getColDataType() != null ? cdt.getColDataType().toString() : "text";
                        List<String> specs = cdt.getColumnSpecs() != null ? cdt.getColumnSpecs() : List.of();
                        boolean notNull = String.join(" ", specs).toUpperCase(Locale.ROOT).contains("NOT NULL");
                        mt.columns.put(colName, new ColumnModel(colName, colType, !notNull, null, false));
                    }
                } else if (op == AlterOperation.DROP && expr.getColumnName() != null) {
                    mt.columns.remove(cleanId(expr.getColumnName()));
                } else if (op == AlterOperation.ALTER && expr.getColumnName() != null) {
                    String colName = cleanId(expr.getColumnName());
                    ColumnModel existing = mt.columns.get(colName);
                    if (existing != null) {
                        String exprText = expr.toString().toUpperCase(Locale.ROOT);
                        boolean nullable = existing.nullable();
                        if (exprText.contains("SET NOT NULL")) {
                            nullable = false;
                        } else if (exprText.contains("DROP NOT NULL")) {
                            nullable = true;
                        }
                        mt.columns.put(colName, new ColumnModel(
                                existing.name(), existing.type(), nullable, existing.defaultExpression(), existing.identityOrGenerated()
                        ));
                    }
                }
            }

        } else if (stmt instanceof CreateIndex ci) {
            String schema = cleanSchema(ci.getTable().getSchemaName());
            String table = cleanId(ci.getTable().getName());
            MutableTable mt = tables.get(schema + "." + table);
            if (mt != null && ci.getIndex() != null) {
                String idxName = cleanId(ci.getIndex().getName());
                List<String> cols = ci.getIndex().getColumnsNames() != null
                        ? ci.getIndex().getColumnsNames().stream().map(this::cleanId).toList()
                        : List.of();
                mt.indexes.add(new IndexModel(idxName, cols, false, "btree"));
            }
        }
    }

    private String cleanSchema(String raw) {
        return (raw == null || raw.isBlank()) ? "public" : cleanId(raw);
    }

    private String cleanId(String raw) {
        return raw == null ? "" : raw.replace("\"", "").trim().toLowerCase(Locale.ROOT);
    }

    private static final class MutableTable {
        final String schemaName;
        final String tableName;
        final Map<String, ColumnModel> columns = new LinkedHashMap<>();
        PrimaryKeyModel primaryKey;
        final List<IndexModel> indexes = new ArrayList<>();

        MutableTable(String schemaName, String tableName) {
            this.schemaName = schemaName;
            this.tableName = tableName;
        }

        TableModel toTableModel() {
            return new TableModel(
                    tableName,
                    new ArrayList<>(columns.values()),
                    primaryKey,
                    List.of(),
                    List.of(),
                    List.of(),
                    new ArrayList<>(indexes)
            );
        }
    }
}