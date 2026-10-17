package com.schemaguard.core.diff;

import com.schemaguard.core.parser.NormalizedOperation;
import com.schemaguard.core.parser.OperationType;
import com.schemaguard.core.parser.ParserStatus;
import com.schemaguard.core.schema.CanonicalSchemaNormalizer;
import com.schemaguard.core.schema.ColumnModel;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaSnapshot.SchemaNamespace;
import com.schemaguard.core.schema.TableModel;
import com.schemaguard.core.schema.TableObjects.PrimaryKeyModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase3SchemaDiffTest {

    private CanonicalSchemaNormalizer normalizer;
    private SchemaDiffEngine diffEngine;

    @BeforeEach
    void setUp() {
        normalizer = new CanonicalSchemaNormalizer();
        diffEngine = new SchemaDiffEngine();
    }

    @Test
    void shouldProduceIdenticalCanonicalHashRegardlessOfOrderingOrTypeAliases() {
        TableModel table1 = new TableModel(
                "Customers",
                List.of(
                        new ColumnModel("email", "VARCHAR(255)", false, null, false),
                        new ColumnModel("age", "int4", true, null, false)
                ),
                new PrimaryKeyModel("customers_pkey", List.of("email")),
                List.of(), List.of(), List.of(), List.of()
        );

        TableModel table2 = new TableModel(
                "customers",
                List.of(
                        new ColumnModel("age", "INTEGER", true, null, false),
                        new ColumnModel("email", "character varying(255)", false, null, false)
                ),
                new PrimaryKeyModel("customers_pkey", List.of("email")),
                List.of(), List.of(), List.of(), List.of()
        );

        SchemaSnapshot snap1 = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("PUBLIC", List.of(table1), List.of(), List.of())));
        SchemaSnapshot snap2 = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(table2), List.of(), List.of())));

        assertEquals(snap1.canonicalHash(), snap2.canonicalHash());
    }

    @Test
    void shouldDetectColumnDropsAdditionsTypeChangesAndRenameCandidates() {
        TableModel baseCustomers = new TableModel(
                "customers",
                List.of(
                        new ColumnModel("id", "uuid", false, null, false),
                        new ColumnModel("email", "text", false, null, false),
                        new ColumnModel("loyalty_points", "integer", true, "0", false)
                ),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );

        TableModel candidateCustomers = new TableModel(
                "customers",
                List.of(
                        new ColumnModel("id", "uuid", false, null, false),
                        new ColumnModel("contact_email", "text", false, null, false), // Dropped email, added contact_email
                        new ColumnModel("loyalty_points", "bigint", false, "0", false) // Widened int -> bigint & set NOT NULL
                ),
                new PrimaryKeyModel("customers_pkey", List.of("id")),
                List.of(), List.of(), List.of(), List.of()
        );

        SchemaSnapshot baseSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(baseCustomers), List.of(), List.of())));
        SchemaSnapshot candSnap = normalizer.normalizeAndHash("16.2",
                List.of(new SchemaNamespace("public", List.of(candidateCustomers), List.of(), List.of())));

        List<NormalizedOperation> ops = List.of(
                NormalizedOperation.create(OperationType.DROP_COLUMN, "public", "customers", "email",
                        Map.of(), "V12__remove_email.sql", 1, 2, ParserStatus.SUPPORTED),
                NormalizedOperation.create(OperationType.ADD_COLUMN, "public", "customers", "contact_email",
                        Map.of(), "V12__remove_email.sql", 3, 4, ParserStatus.SUPPORTED)
        );

        SchemaDiffResult diff = diffEngine.compare(baseSnap, candSnap, ops);

        assertFalse(diff.identical());
        assertTrue(diff.changes().stream().anyMatch(c ->
                c.changeType() == ChangeType.COLUMN_DROPPED
                        && "email".equals(c.objectName())
                        && "V12__remove_email.sql".equals(c.sourceFile())
                        && c.mappedToSource()));

        assertTrue(diff.changes().stream().anyMatch(c ->
                c.changeType() == ChangeType.COLUMN_ADDED && "contact_email".equals(c.objectName())));

        assertTrue(diff.changes().stream().anyMatch(c ->
                c.changeType() == ChangeType.RENAME_CANDIDATE && "email -> contact_email".equals(c.objectName())));

        assertTrue(diff.changes().stream().anyMatch(c ->
                c.changeType() == ChangeType.COLUMN_TYPE_CHANGED && "loyalty_points".equals(c.objectName())));

        assertTrue(diff.changes().stream().anyMatch(c ->
                c.changeType() == ChangeType.COLUMN_NULLABILITY_CHANGED && "loyalty_points".equals(c.objectName())));
    }
}