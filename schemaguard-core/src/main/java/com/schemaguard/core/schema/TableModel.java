package com.schemaguard.core.schema;

import com.schemaguard.core.schema.TableObjects.*;
import java.util.List;

public record TableModel(
    String name,
    List<ColumnModel> columns,
    PrimaryKeyModel primaryKey,
    List<UniqueConstraintModel> uniqueConstraints,
    List<ForeignKeyModel> foreignKeys,
    List<CheckConstraintModel> checkConstraints,
    List<IndexModel> indexes
) implements Comparable<TableModel> {

    @Override
    public int compareTo(TableModel other) {
        return this.name.compareTo(other.name);
    }
}