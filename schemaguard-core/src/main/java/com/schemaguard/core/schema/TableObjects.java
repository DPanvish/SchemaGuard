package com.schemaguard.core.schema;

import java.util.List;

public final class TableObjects {

    private TableObjects() {}

    public record PrimaryKeyModel(
        String name,
        List<String> columns
    ) {}

    public record UniqueConstraintModel(
        String name,
        List<String> columns
    ) implements Comparable<UniqueConstraintModel> {
        @Override
        public int compareTo(UniqueConstraintModel other) {
            return this.name.compareTo(other.name);
        }
    }

    public record ForeignKeyModel(
        String name,
        List<String> localColumns,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns,
        String onDeleteAction,
        String onUpdateAction
    ) implements Comparable<ForeignKeyModel> {
        @Override
        public int compareTo(ForeignKeyModel other) {
            return this.name.compareTo(other.name);
        }
    }

    public record CheckConstraintModel(
        String name,
        String expression
    ) implements Comparable<CheckConstraintModel> {
        @Override
        public int compareTo(CheckConstraintModel other) {
            return this.name.compareTo(other.name);
        }
    }

    public record IndexModel(
        String name,
        List<String> columns,
        boolean unique,
        String indexMethod
    ) implements Comparable<IndexModel> {
        @Override
        public int compareTo(IndexModel other) {
            return this.name.compareTo(other.name);
        }
    }

    public record ViewModel(
        String name,
        String normalizedDefinition
    ) implements Comparable<ViewModel> {
        @Override
        public int compareTo(ViewModel other) {
            return this.name.compareTo(other.name);
        }
    }

    public record SequenceModel(
        String name,
        String dataType,
        long startValue,
        long incrementBy
    ) implements Comparable<SequenceModel> {
        @Override
        public int compareTo(SequenceModel other) {
            return this.name.compareTo(other.name);
        }
    }
}