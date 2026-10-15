package com.schemaguard.core.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableObjects.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CanonicalSchemaNormalizer {

    private static final Logger log = LoggerFactory.getLogger(CanonicalSchemaNormalizer.class);
    public static final String CURRENT_MODEL_VERSION = "v1.0";

    private static final Map<String, String> PG_TYPE_ALIASES = Map.ofEntries(
        Map.entry("int", "integer"),
        Map.entry("int4", "integer"),
        Map.entry("int8", "bigint"),
        Map.entry("int2", "smallint"),
        Map.entry("bool", "boolean"),
        Map.entry("varchar", "character varying"),
        Map.entry("char", "character"),
        Map.entry("bpchar", "character"),
        Map.entry("float8", "double precision"),
        Map.entry("float4", "real"),
        Map.entry("timestamptz", "timestamp with time zone"),
        Map.entry("timetz", "time with time zone"),
        Map.entry("decimal", "numeric")
    );

    private final ObjectMapper canonicalMapper;

    public CanonicalSchemaNormalizer() {
        this.canonicalMapper = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .build();
    }

    /**
     * Normalizes all objects, sorts collections deterministically, and computes the SHA-256 canonicalHash.
     */
    public SchemaSnapshot normalizeAndHash(String engineVersion, List<SchemaNamespace> rawSchemas) {
        log.debug("Normalizing schema snapshot for PostgreSQL version {}", engineVersion);

        List<SchemaNamespace> normalizedSchemas = (rawSchemas == null ? List.<SchemaNamespace>of() : rawSchemas)
                .stream()
                .map(this::normalizeNamespace)
                .sorted()
                .toList();

        // Build intermediate record without hash to serialize deterministically
        SchemaSnapshot unhashed = new SchemaSnapshot(
                CURRENT_MODEL_VERSION,
                "PostgreSQL",
                engineVersion != null ? engineVersion.trim() : "unknown",
                normalizedSchemas,
                ""
        );

        String hash = computeCanonicalHash(unhashed);
        log.info("Generated canonical schema snapshot [version={}, schemas={}, hash={}]",
                CURRENT_MODEL_VERSION, normalizedSchemas.size(), hash);

        return new SchemaSnapshot(
                CURRENT_MODEL_VERSION,
                "PostgreSQL",
                unhashed.engineVersion(),
                normalizedSchemas,
                hash
        );
    }

    public String normalizePostgresType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "unknown";
        }
        String cleaned = rawType.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        // Handle parameterized types like varchar(255) -> character varying(255)
        int parenIndex = cleaned.indexOf('(');
        if (parenIndex > 0) {
            String baseType = cleaned.substring(0, parenIndex).trim();
            String params = cleaned.substring(parenIndex);
            String mappedBase = PG_TYPE_ALIASES.getOrDefault(baseType, baseType);
            return mappedBase + params;
        }
        return PG_TYPE_ALIASES.getOrDefault(cleaned, cleaned);
    }

    private SchemaNamespace normalizeNamespace(SchemaNamespace ns) {
        List<TableModel> tables = safeList(ns.tables()).stream()
                .map(this::normalizeTable)
                .sorted()
                .toList();

        List<ViewModel> views = safeList(ns.views()).stream()
                .map(v -> new ViewModel(cleanId(v.name()), normalizeSqlExpression(v.normalizedDefinition())))
                .sorted()
                .toList();

        List<SequenceModel> sequences = safeList(ns.sequences()).stream()
                .map(s -> new SequenceModel(cleanId(s.name()), normalizePostgresType(s.dataType()), s.startValue(), s.incrementBy()))
                .sorted()
                .toList();

        return new SchemaNamespace(cleanId(ns.name()), tables, views, sequences);
    }

    private TableModel normalizeTable(TableModel table) {
        List<ColumnModel> columns = safeList(table.columns()).stream()
                .map(c -> new ColumnModel(
                        cleanId(c.name()),
                        normalizePostgresType(c.type()),
                        c.nullable(),
                        normalizeSqlExpression(c.defaultExpression()),
                        c.identityOrGenerated()
                ))
                .sorted()
                .toList();

        PrimaryKeyModel pk = table.primaryKey() == null ? null : new PrimaryKeyModel(
                cleanId(table.primaryKey().name()),
                safeList(table.primaryKey().columns()).stream().map(this::cleanId).toList()
        );

        List<UniqueConstraintModel> uniques = safeList(table.uniqueConstraints()).stream()
                .map(u -> new UniqueConstraintModel(cleanId(u.name()), safeList(u.columns()).stream().map(this::cleanId).toList()))
                .sorted()
                .toList();

        List<ForeignKeyModel> fks = safeList(table.foreignKeys()).stream()
                .map(fk -> new ForeignKeyModel(
                        cleanId(fk.name()),
                        safeList(fk.localColumns()).stream().map(this::cleanId).toList(),
                        cleanId(fk.referencedSchema()),
                        cleanId(fk.referencedTable()),
                        safeList(fk.referencedColumns()).stream().map(this::cleanId).toList(),
                        cleanAction(fk.onDeleteAction()),
                        cleanAction(fk.onUpdateAction())
                ))
                .sorted()
                .toList();

        List<CheckConstraintModel> checks = safeList(table.checkConstraints()).stream()
                .map(ch -> new CheckConstraintModel(cleanId(ch.name()), normalizeSqlExpression(ch.expression())))
                .sorted()
                .toList();

        List<IndexModel> indexes = safeList(table.indexes()).stream()
                .map(idx -> new IndexModel(
                        cleanId(idx.name()),
                        safeList(idx.columns()).stream().map(this::cleanId).toList(),
                        idx.unique(),
                        cleanId(idx.indexMethod() != null ? idx.indexMethod() : "btree")
                ))
                .sorted()
                .toList();

        return new TableModel(cleanId(table.name()), columns, pk, uniques, fks, checks, indexes);
    }

    private String computeCanonicalHash(SchemaSnapshot snapshotWithoutHash) {
        try {
            String canonicalJson = canonicalMapper.writeValueAsString(snapshotWithoutHash);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            log.error("Failed to compute canonical hash for schema snapshot", e);
            throw new IllegalStateException("Unable to compute canonical schema hash", e);
        }
    }

    private String cleanId(String id) {
        return id == null ? "" : id.replace("\"", "").trim().toLowerCase(Locale.ROOT);
    }

    private String cleanAction(String action) {
        return action == null ? "no action" : action.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSqlExpression(String expr) {
        return expr == null || expr.isBlank() ? null : expr.trim().replaceAll("\\s+", " ");
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}