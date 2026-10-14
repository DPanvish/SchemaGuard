package com.schemaguard.core.parser;

import java.util.Map;
import java.util.UUID;

public record NormalizedOperation(
    String operationId,
    OperationType operationType,
    String schemaName,
    String objectName,
    String columnName,      // Nullable where not applicable
    Map<String, Object> arguments, // Structured operation metadata (e.g., lock requirements, new types)
    String sourceFile,
    Integer startLine,
    Integer endLine,
    ParserStatus parserStatus
) {
    // Static factory method for quick instantiation
    public static NormalizedOperation create(
            OperationType type, 
            String schema, 
            String object, 
            String column, 
            Map<String, Object> args, 
            String file, 
            Integer start, 
            Integer end, 
            ParserStatus status) {
        return new NormalizedOperation(
                UUID.randomUUID().toString(), 
                type, schema, object, column, args, file, start, end, status
        );
    }
}