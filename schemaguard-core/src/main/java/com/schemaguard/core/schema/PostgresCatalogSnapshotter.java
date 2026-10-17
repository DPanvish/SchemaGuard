package com.schemaguard.core.schema;

import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableObjects.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

@Service
public class PostgresCatalogSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(PostgresCatalogSnapshotter.class);
    public static final String PINNED_POSTGRES_IMAGE = "postgres:16-alpine";

    private final CanonicalSchemaNormalizer normalizer;

    public PostgresCatalogSnapshotter(CanonicalSchemaNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Replays the provided SQL migration files in an isolated disposable PostgreSQL container
     * and captures the canonical schema snapshot.
     */
    public SchemaSnapshot replayAndCaptureInSandbox(List<File> orderedMigrations) {
        log.info("Starting isolated PostgreSQL container ({}) to replay {} migration(s)",
                PINNED_POSTGRES_IMAGE, orderedMigrations.size());

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse(PINNED_POSTGRES_IMAGE))
                .withDatabaseName("schemaguard_sandbox")
                .withUsername("sg_sandbox_user")
                .withPassword("sg_ephemeral_pwd")) {

            postgres.start();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

                for (File migrationFile : orderedMigrations) {
                    applyMigrationFile(connection, migrationFile);
                }

                return inspectCatalog(connection);
            }
        } catch (SQLException e) {
            log.error("Database failure during isolated schema reconstruction", e);
            throw new IllegalStateException("Schema reconstruction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Inspects an active PostgreSQL JDBC connection and extracts the canonical SchemaSnapshot.
     */
    public SchemaSnapshot inspectCatalog(Connection connection) throws SQLException {
        String serverVersion = connection.getMetaData().getDatabaseProductVersion();
        log.debug("Inspecting PostgreSQL catalogs (version={})", serverVersion);

        List<String> userSchemas = loadUserSchemas(connection);
        List<SchemaNamespace> namespaces = new ArrayList<>();

        for (String schemaName : userSchemas) {
            List<TableModel> tables = loadTables(connection, schemaName);
            List<ViewModel> views = loadViews(connection, schemaName);
            List<SequenceModel> sequences = loadSequences(connection, schemaName);
            namespaces.add(new SchemaNamespace(schemaName, tables, views, sequences));
        }

        return normalizer.normalizeAndHash(serverVersion, namespaces);
    }

    private void applyMigrationFile(Connection connection, File file) throws SQLException {
        log.debug("Applying migration file in sandbox: {}", file.getName());
        try {
            String sql = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            try (Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(15); // Bounded execution timeout per threat model
                stmt.execute(sql);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read migration file: " + file.getName(), e);
        }
    }

    private List<String> loadUserSchemas(Connection conn) throws SQLException {
        List<String> schemas = new ArrayList<>();
        String sql = """
                SELECT schema_name
                FROM information_schema.schemata
                WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                  AND schema_name NOT LIKE 'pg_temp_%'
                  AND schema_name NOT LIKE 'pg_toast_temp_%'
                ORDER BY schema_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                schemas.add(rs.getString("schema_name"));
            }
        }
        return schemas;
    }

    private List<TableModel> loadTables(Connection conn, String schema) throws SQLException {
        List<TableModel> tables = new ArrayList<>();
        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    tables.add(new TableModel(
                            tableName,
                            loadColumns(conn, schema, tableName),
                            loadPrimaryKey(conn, schema, tableName),
                            loadUniqueConstraints(conn, schema, tableName),
                            loadForeignKeys(conn, schema, tableName),
                            loadCheckConstraints(conn, schema, tableName),
                            loadIndexes(conn, schema, tableName)
                    ));
                }
            }
        }
        return tables;
    }

    private List<ColumnModel> loadColumns(Connection conn, String schema, String table) throws SQLException {
        List<ColumnModel> cols = new ArrayList<>();
        String sql = """
                SELECT column_name, data_type, is_nullable, column_default, is_identity, is_generated
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY column_name
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                    boolean identityOrGen = "YES".equalsIgnoreCase(rs.getString("is_identity"))
                            || !"NEVER".equalsIgnoreCase(rs.getString("is_generated"));
                    cols.add(new ColumnModel(
                            rs.getString("column_name"),
                            rs.getString("data_type"),
                            nullable,
                            rs.getString("column_default"),
                            identityOrGen
                    ));
                }
            }
        }
        return cols;
    }

    private PrimaryKeyModel loadPrimaryKey(Connection conn, String schema, String table) throws SQLException {
        String sql = """
                SELECT tc.constraint_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY'
                ORDER BY kcu.ordinal_position
                """;
        String pkName = null;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pkName = rs.getString("constraint_name");
                    columns.add(rs.getString("column_name"));
                }
            }
        }
        return pkName == null ? null : new PrimaryKeyModel(pkName, columns);
    }

    private List<UniqueConstraintModel> loadUniqueConstraints(Connection conn, String schema, String table) throws SQLException {
        String sql = """
                SELECT tc.constraint_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'UNIQUE'
                ORDER BY tc.constraint_name, kcu.ordinal_position
                """;
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    grouped.computeIfAbsent(rs.getString("constraint_name"), k -> new ArrayList<>())
                            .add(rs.getString("column_name"));
                }
            }
        }
        return grouped.entrySet().stream()
                .map(e -> new UniqueConstraintModel(e.getKey(), e.getValue()))
                .toList();
    }

    private List<ForeignKeyModel> loadForeignKeys(Connection conn, String schema, String table) throws SQLException {
        String sql = """
                SELECT tc.constraint_name,
                       kcu.column_name,
                       ccu.table_schema AS foreign_table_schema,
                       ccu.table_name AS foreign_table_name,
                       ccu.column_name AS foreign_column_name,
                       rc.delete_rule,
                       rc.update_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
                JOIN information_schema.referential_constraints rc
                  ON rc.constraint_name = tc.constraint_name AND rc.constraint_schema = tc.table_schema
                WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = ? AND tc.table_name = ?
                ORDER BY tc.constraint_name, kcu.ordinal_position
                """;
        List<ForeignKeyModel> fks = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fks.add(new ForeignKeyModel(
                            rs.getString("constraint_name"),
                            List.of(rs.getString("column_name")),
                            rs.getString("foreign_table_schema"),
                            rs.getString("foreign_table_name"),
                            List.of(rs.getString("foreign_column_name")),
                            rs.getString("delete_rule"),
                            rs.getString("update_rule")
                    ));
                }
            }
        }
        return fks;
    }

    private List<CheckConstraintModel> loadCheckConstraints(Connection conn, String schema, String table) throws SQLException {
        String sql = """
                SELECT tc.constraint_name, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON tc.constraint_name = cc.constraint_name AND tc.constraint_schema = cc.constraint_schema
                WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'CHECK'
                  AND tc.constraint_name NOT LIKE '%_not_null'
                ORDER BY tc.constraint_name
                """;
        List<CheckConstraintModel> checks = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    checks.add(new CheckConstraintModel(
                            rs.getString("constraint_name"),
                            rs.getString("check_clause")
                    ));
                }
            }
        }
        return checks;
    }

    private List<IndexModel> loadIndexes(Connection conn, String schema, String table) throws SQLException {
        String sql = """
                SELECT i.relname AS index_name,
                       am.amname AS index_method,
                       ix.indisunique AS is_unique,
                       a.attname AS column_name
                FROM pg_class t
                JOIN pg_namespace ns ON ns.oid = t.relnamespace
                JOIN pg_index ix ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_am am ON i.relam = am.oid
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                WHERE ns.nspname = ? AND t.relname = ? AND NOT ix.indisprimary
                ORDER BY i.relname, a.attnum
                """;
        Map<String, IndexModel> indexMap = new LinkedHashMap<>();
        Map<String, List<String>> indexCols = new LinkedHashMap<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idxName = rs.getString("index_name");
                    indexCols.computeIfAbsent(idxName, k -> new ArrayList<>()).add(rs.getString("column_name"));
                    indexMap.putIfAbsent(idxName, new IndexModel(
                            idxName,
                            List.of(),
                            rs.getBoolean("is_unique"),
                            rs.getString("index_method")
                    ));
                }
            }
        }

        return indexMap.entrySet().stream()
                .map(e -> new IndexModel(
                        e.getKey(),
                        indexCols.getOrDefault(e.getKey(), List.of()),
                        e.getValue().unique(),
                        e.getValue().indexMethod()
                ))
                .toList();
    }

    private List<ViewModel> loadViews(Connection conn, String schema) throws SQLException {
        String sql = """
                SELECT table_name, view_definition
                FROM information_schema.views
                WHERE table_schema = ?
                ORDER BY table_name
                """;
        List<ViewModel> views = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    views.add(new ViewModel(rs.getString("table_name"), rs.getString("view_definition")));
                }
            }
        }
        return views;
    }

    private List<SequenceModel> loadSequences(Connection conn, String schema) throws SQLException {
        String sql = """
                SELECT sequence_name, data_type, start_value, increment
                FROM information_schema.sequences
                WHERE sequence_schema = ?
                ORDER BY sequence_name
                """;
        List<SequenceModel> sequences = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sequences.add(new SequenceModel(
                            rs.getString("sequence_name"),
                            rs.getString("data_type"),
                            rs.getLong("start_value"),
                            rs.getLong("increment")
                    ));
                }
            }
        }
        return sequences;
    }
}