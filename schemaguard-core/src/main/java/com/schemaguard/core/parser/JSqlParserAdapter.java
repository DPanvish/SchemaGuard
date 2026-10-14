package com.schemaguard.core.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JSqlParserAdapter implements MigrationParser {

    private static final Logger log = LoggerFactory.getLogger(JSqlParserAdapter.class);
    private static final String DEFAULT_SCHEMA = "public";

    @Override
    public List<NormalizedOperation> parse(File migrationFile) {
        log.info("Starting SQL analysis for migration file: {}", migrationFile.getName());
        List<NormalizedOperation> operations = new ArrayList<>();

        String sqlContent;
        try {
            sqlContent = Files.readString(migrationFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read migration file: {}", migrationFile.getName(), e);
            throw new IllegalStateException("Cannot read migration file: " + migrationFile.getName(), e);
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sqlContent);
            for (Statement stmt : statements.getStatements()) {
                operations.addAll(normalizeStatement(stmt, migrationFile.getName()));
            }
        } catch (JSQLParserException e) {
            // Conservative principle: never silently ignore unparseable SQL
            log.warn("Unsupported or unparseable SQL syntax detected in file: {}", migrationFile.getName());
            log.debug("Parser exception details for {}: {}", migrationFile.getName(), e.getMessage());

            Map<String, Object> metadata = Map.of("reason", "JSqlParser could not parse syntax");
            operations.add(NormalizedOperation.create(
                    OperationType.UNKNOWN_OR_UNSUPPORTED,
                    DEFAULT_SCHEMA,
                    "UNKNOWN",
                    null,
                    metadata,
                    migrationFile.getName(),
                    1,
                    1,
                    ParserStatus.UNSUPPORTED
            ));
        }

        log.info("Completed SQL analysis for {}: extracted {} operation(s)", migrationFile.getName(), operations.size());
        return operations;
    }

    private List<NormalizedOperation> normalizeStatement(Statement stmt, String fileName) {
        List<NormalizedOperation> result = new ArrayList<>();
        int[] lines = extractLineNumbers(stmt);
        int startLine = lines[0];
        int endLine = lines[1];

        if (stmt instanceof CreateTable createTable) {
            Table table = createTable.getTable();
            String schema = resolveSchema(table.getSchemaName());
            String tableName = cleanIdentifier(table.getName());

            Map<String, Object> args = new HashMap<>();
            int columnCount = createTable.getColumnDefinitions() != null ? createTable.getColumnDefinitions().size() : 0;
            args.put("columnCount", columnCount);
            args.put("ifNotExists", createTable.isIfNotExists());

            log.debug("Normalized CREATE_TABLE on {}.{} at lines {}-{}", schema, tableName, startLine, endLine);
            result.add(NormalizedOperation.create(
                    OperationType.CREATE_TABLE, schema, tableName, null, args, fileName, startLine, endLine, ParserStatus.SUPPORTED
            ));

        } else if (stmt instanceof Drop drop) {
            String objectType = drop.getType() != null ? drop.getType().toUpperCase() : "";
            String schema = resolveSchema(drop.getName().getSchemaName());
            String objectName = cleanIdentifier(drop.getName().getName());

            if ("TABLE".equals(objectType)) {
                Map<String, Object> args = Map.of("ifExists", drop.isIfExists());
                log.debug("Normalized DROP_TABLE on {}.{} at lines {}-{}", schema, objectName, startLine, endLine);
                result.add(NormalizedOperation.create(
                        OperationType.DROP_TABLE, schema, objectName, null, args, fileName, startLine, endLine, ParserStatus.SUPPORTED
                ));
            } else {
                result.add(createUnsupportedOperation(fileName, schema, objectName, startLine, endLine, "Unsupported DROP type: " + objectType));
            }

        } else if (stmt instanceof CreateIndex createIndex) {
            Table table = createIndex.getTable();
            String schema = resolveSchema(table.getSchemaName());
            String tableName = cleanIdentifier(table.getName());
            String indexName = createIndex.getIndex() != null ? cleanIdentifier(createIndex.getIndex().getName()) : "UNNAMED_INDEX";

            Map<String, Object> args = new HashMap<>();
            args.put("indexName", indexName);
            // Note: PostgreSQL CONCURRENTLY check will be enriched via raw token inspection in Risk Engine
            args.put("rawStatementType", "CREATE_INDEX");

            log.debug("Normalized ADD_INDEX ({}) on {}.{} at lines {}-{}", indexName, schema, tableName, startLine, endLine);
            result.add(NormalizedOperation.create(
                    OperationType.ADD_INDEX, schema, tableName, null, args, fileName, startLine, endLine, ParserStatus.SUPPORTED
            ));

        } else if (stmt instanceof Alter alter) {
            result.addAll(normalizeAlterStatement(alter, fileName, startLine, endLine));

        } else {
            String stmtClass = stmt.getClass().getSimpleName();
            log.warn("Statement type {} at line {} in {} is outside supported scope", stmtClass, startLine, fileName);
            result.add(createUnsupportedOperation(fileName, DEFAULT_SCHEMA, "UNKNOWN", startLine, endLine, "Unsupported statement class: " + stmtClass));
        }

        return result;
    }

    private List<NormalizedOperation> normalizeAlterStatement(Alter alter, String fileName, int startLine, int endLine) {
        List<NormalizedOperation> alterOps = new ArrayList<>();
        Table table = alter.getTable();
        String schema = resolveSchema(table.getSchemaName());
        String tableName = cleanIdentifier(table.getName());

        if (alter.getAlterExpressions() == null || alter.getAlterExpressions().isEmpty()) {
            alterOps.add(createUnsupportedOperation(fileName, schema, tableName, startLine, endLine, "Empty or unrecognized ALTER TABLE expression"));
            return alterOps;
        }

        for (AlterExpression expr : alter.getAlterExpressions()) {
            AlterOperation op = expr.getOperation();

            if (op == AlterOperation.ADD && expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                for (AlterExpression.ColumnDataType colType : expr.getColDataTypeList()) {
                    String colName = cleanIdentifier(colType.getColumnName());
                    String dataType = colType.getColDataType() != null ? colType.getColDataType().getDataType() : "UNKNOWN";
                    Map<String, Object> args = Map.of("dataType", dataType);

                    log.debug("Normalized ADD_COLUMN ({}) on {}.{}", colName, schema, tableName);
                    alterOps.add(NormalizedOperation.create(
                            OperationType.ADD_COLUMN, schema, tableName, colName, args, fileName, startLine, endLine, ParserStatus.SUPPORTED
                    ));
                }
            } else if (op == AlterOperation.DROP && expr.getColumnName() != null) {
                String colName = cleanIdentifier(expr.getColumnName());
                log.debug("Normalized DROP_COLUMN ({}) on {}.{}", colName, schema, tableName);
                alterOps.add(NormalizedOperation.create(
                        OperationType.DROP_COLUMN, schema, tableName, colName, Map.of(), fileName, startLine, endLine, ParserStatus.SUPPORTED
                ));
            } else if (op == AlterOperation.ALTER && expr.getColumnName() != null) {
                String colName = cleanIdentifier(expr.getColumnName());
                log.debug("Normalized ALTER_COLUMN_TYPE/CONSTRAINT ({}) on {}.{}", colName, schema, tableName);
                alterOps.add(NormalizedOperation.create(
                        OperationType.ALTER_COLUMN_TYPE, schema, tableName, colName, Map.of(), fileName, startLine, endLine, ParserStatus.PARTIAL
                ));
            } else if (op == AlterOperation.ADD && expr.getIndex() != null) {
                Map<String, Object> args = Map.of("constraintType", expr.getIndex().getType());
                alterOps.add(NormalizedOperation.create(
                        OperationType.ADD_CONSTRAINT, schema, tableName, null, args, fileName, startLine, endLine, ParserStatus.SUPPORTED
                ));
            } else {
                alterOps.add(createUnsupportedOperation(fileName, schema, tableName, startLine, endLine, "Unsupported ALTER operation: " + op));
            }
        }

        return alterOps;
    }

    private NormalizedOperation createUnsupportedOperation(String fileName, String schema, String objectName, int startLine, int endLine, String reason) {
        return NormalizedOperation.create(
                OperationType.UNKNOWN_OR_UNSUPPORTED,
                schema,
                objectName,
                null,
                Map.of("reason", reason),
                fileName,
                startLine,
                endLine,
                ParserStatus.UNSUPPORTED
        );
    }

    private int[] extractLineNumbers(Statement stmt) {
        if (stmt instanceof ASTNodeAccess nodeAccess) {
            SimpleNode node = nodeAccess.getASTNode();
            if (node != null && node.jjtGetFirstToken() != null) {
                int start = node.jjtGetFirstToken().beginLine;
                int end = node.jjtGetLastToken() != null ? node.jjtGetLastToken().endLine : start;
                return new int[]{start, end};
            }
        }
        return new int[]{1, 1};
    }

    private String resolveSchema(String rawSchema) {
        return (rawSchema == null || rawSchema.isBlank()) ? DEFAULT_SCHEMA : cleanIdentifier(rawSchema);
    }

    private String cleanIdentifier(String identifier) {
        return identifier == null ? "" : identifier.replace("\"", "").trim().toLowerCase();
    }
}