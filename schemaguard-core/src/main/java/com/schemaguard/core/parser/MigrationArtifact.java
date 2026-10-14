package com.schemaguard.core.parser;

import java.util.List;

public record MigrationArtifact(
    String fileName,
    String filePath,
    String checksum,
    ParserStatus overallStatus,
    List<NormalizedOperation> operations
) {
    public static MigrationArtifact of(String fileName, String filePath, String checksum, List<NormalizedOperation> operations) {
        ParserStatus status = ParserStatus.SUPPORTED;
        for (NormalizedOperation op : operations) {
            if (op.parserStatus() == ParserStatus.UNSUPPORTED) {
                status = ParserStatus.UNSUPPORTED;
                break;
            } else if (op.parserStatus() == ParserStatus.PARTIAL) {
                status = ParserStatus.PARTIAL;
            }
        }
        return new MigrationArtifact(fileName, filePath, checksum, status, operations);
    }
}