package com.schemaguard.core.diff;

import java.util.List;

public record SchemaDiffResult(
    String baseCanonicalHash,
    String candidateCanonicalHash,
    boolean identical,
    List<SchemaChange> changes,
    int unmappedChangesCount
) {}