package com.schemaguard.core.schema;

public record ColumnModel(
    String name,
    String type,
    boolean nullable,
    String defaultExpression,
    boolean identityOrGenerated
) implements Comparable<ColumnModel> {

    @Override
    public int compareTo(ColumnModel other) {
        return this.name.compareTo(other.name);
    }
}