package com.schemaguard.core.parser;

import java.io.File;
import java.util.List;

public interface MigrationParser {
    /**
     * Parses a SQL migration file into a list of normalized operations.
     * Preserves source file and line locations.
     * 
     * @param migrationFile The SQL file to parse.
     * @return A list of operations. Unsupported statements are included but marked explicitly.
     */
    List<NormalizedOperation> parse(File migrationFile);
}