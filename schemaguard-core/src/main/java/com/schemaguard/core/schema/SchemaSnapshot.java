package com.schemaguard.core.schema;

import com.schemaguard.core.schema.TableObjects.SequenceModel;
import com.schemaguard.core.schema.TableObjects.ViewModel;

import java.util.List;

public record SchemaSnapshot(
    String schemaModelVersion,
    String engine,
    String engineVersion,
    List<SchemaNamespace> schemas,
    String canonicalHash
) {
    public record SchemaNamespace(
        String name,
        List<TableModel> tables,
        List<ViewModel> views,
        List<SequenceModel> sequences
    ) implements Comparable<SchemaNamespace> {
        @Override
        public int compareTo(SchemaNamespace other) {
            return this.name.compareTo(other.name);
        }
    }
}