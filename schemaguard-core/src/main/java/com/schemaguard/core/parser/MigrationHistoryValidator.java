package com.schemaguard.core.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MigrationHistoryValidator {

    private static final Logger log = LoggerFactory.getLogger(MigrationHistoryValidator.class);

    public record TamperedMigration(
        String fileName,
        String expectedBaselineChecksum,
        String actualCandidateChecksum
    ) {}

    /**
     * Compares baseline migration checksums against candidate migration artifacts
     * to detect modified historical migration files (Rule SG007 foundation).
     */
    public List<TamperedMigration> detectModifiedHistory(
            Map<String, String> baselineChecksumsByFileName,
            List<MigrationArtifact> candidateArtifacts) {

        log.debug("Validating {} candidate migration(s) against {} baseline record(s)",
                candidateArtifacts.size(), baselineChecksumsByFileName.size());

        List<TamperedMigration> tamperedMigrations = new ArrayList<>();

        for (MigrationArtifact candidate : candidateArtifacts) {
            String baselineChecksum = baselineChecksumsByFileName.get(candidate.fileName());
            if (baselineChecksum != null && !baselineChecksum.equals(candidate.checksum())) {
                log.warn("Tampered historical migration detected! File: {}, Baseline Checksum: {}, Candidate Checksum: {}",
                        candidate.fileName(), baselineChecksum, candidate.checksum());

                tamperedMigrations.add(new TamperedMigration(
                        candidate.fileName(),
                        baselineChecksum,
                        candidate.checksum()
                ));
            }
        }

        return tamperedMigrations;
    }
}