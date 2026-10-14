package com.schemaguard.core.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class MigrationChecksumService {

    private static final Logger log = LoggerFactory.getLogger(MigrationChecksumService.class);

    /**
     * Computes a deterministic SHA-256 checksum for a SQL migration file.
     * Normalizes CRLF to LF so checksums remain identical across Windows, macOS, and Linux CI.
     */
    public String calculateChecksum(File file) {
        log.debug("Calculating SHA-256 checksum for file: {}", file.getName());
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String normalizedContent = content.replace("\r\n", "\n").trim();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalizedContent.getBytes(StandardCharsets.UTF_8));
            String checksum = HexFormat.of().formatHex(hashBytes);

            log.info("Computed checksum for {}: {}", file.getName(), checksum);
            return checksum;
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Failed to calculate checksum for file: {}", file.getName(), e);
            throw new IllegalStateException("Could not compute migration checksum for " + file.getName(), e);
        }
    }
}