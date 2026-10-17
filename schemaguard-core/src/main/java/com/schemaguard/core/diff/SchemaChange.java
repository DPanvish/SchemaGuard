package com.schemaguard.core.diff;

public record SchemaChange(
    ChangeType changeType,
    String schemaName,
    String tableName,
    String objectName,      // Column, constraint, index, or view name
    String beforeValue,
    String afterValue,
    String sourceFile,      // Attached when a reliable mapping exists
    Integer sourceLine,
    boolean mappedToSource, // False if change could not be mapped confidently to an input statement
    String details
) {
    public String fullyQualifiedObject() {
        if (objectName == null || objectName.isBlank() || objectName.equals(tableName)) {
            return schemaName + "." + tableName;
        }
        return schemaName + "." + tableName + "." + objectName;
    }
}