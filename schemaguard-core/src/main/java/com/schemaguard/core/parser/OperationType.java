package com.schemaguard.core.parser;

public enum OperationType {
    CREATE_TABLE,
    DROP_TABLE,
    ADD_COLUMN,
    DROP_COLUMN,
    ALTER_COLUMN_TYPE,
    ADD_CONSTRAINT,
    ADD_INDEX,
    UNKNOWN_OR_UNSUPPORTED
}