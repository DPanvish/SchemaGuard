package com.schemaguard.core.dependencies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.schemaguard.core.dependencies.ServiceContractManifest.ServiceDefinition;
import com.schemaguard.core.dependencies.ServiceContractManifest.TableReadContract;
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
import java.util.Map;

@Service
public class ServiceContractLoader {

    private static final Logger log = LoggerFactory.getLogger(ServiceContractLoader.class);
    public static final int SUPPORTED_CONTRACT_VERSION = 1;

    private final ObjectMapper strictYamlMapper;

    public ServiceContractLoader() {
        // Section 8.4: Reject invalid/unknown fields instead of silently ignoring them
        this.strictYamlMapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    }

    public record LoadedContractArtifact(
        String fileName,
        String checksum,
        ServiceContractManifest manifest
    ) {}

    public LoadedContractArtifact loadAndValidate(File contractFile) {
        log.info("Loading and validating service contract manifest: {}", contractFile.getName());
        try {
            String yamlContent = Files.readString(contractFile.toPath(), StandardCharsets.UTF_8);
            return loadAndValidateFromString(contractFile.getName(), yamlContent);
        } catch (IOException e) {
            log.error("Failed to read service contract file: {}", contractFile.getName(), e);
            throw new IllegalArgumentException("Could not read contract manifest: " + contractFile.getName(), e);
        }
    }

    public LoadedContractArtifact loadAndValidateFromString(String sourceName, String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("Service contract manifest is empty: " + sourceName);
        }

        try {
            ServiceContractManifest manifest = strictYamlMapper.readValue(yamlContent, ServiceContractManifest.class);
            validateSemantics(sourceName, manifest);
            String checksum = sha256(yamlContent);

            log.info("Validated service contract manifest {} [version={}, services={}, checksum={}]",
                    sourceName, manifest.version(), manifest.services().size(), checksum);

            return new LoadedContractArtifact(sourceName, checksum, manifest);
        } catch (IOException e) {
            log.error("Schema validation failed for service contract manifest {}: {}", sourceName, e.getMessage());
            throw new IllegalArgumentException("Invalid service contract YAML in " + sourceName + ": " + e.getMessage(), e);
        }
    }

    private void validateSemantics(String sourceName, ServiceContractManifest manifest) {
        if (manifest.version() != SUPPORTED_CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported contract version " + manifest.version()
                    + " in " + sourceName + ". Expected version: " + SUPPORTED_CONTRACT_VERSION);
        }
        if (manifest.services() == null || manifest.services().isEmpty()) {
            throw new IllegalArgumentException("Contract manifest " + sourceName + " must declare at least one service");
        }

        for (Map.Entry<String, ServiceDefinition> entry : manifest.services().entrySet()) {
            String serviceName = entry.getKey();
            ServiceDefinition def = entry.getValue();
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("Service name cannot be blank in " + sourceName);
            }
            if (def == null || def.reads() == null || def.reads().isEmpty()) {
                throw new IllegalArgumentException("Service '" + serviceName + "' must declare at least one read entry");
            }
            for (TableReadContract read : def.reads()) {
                if (read.table() == null || read.table().isBlank() || !read.table().contains(".")) {
                    throw new IllegalArgumentException("Service '" + serviceName
                            + "' must specify a schema-qualified table name (e.g., public.customers)");
                }
                if (read.columns() == null || read.columns().isEmpty()) {
                    throw new IllegalArgumentException("Service '" + serviceName
                            + "' must declare at least one column for table " + read.table());
                }
            }
        }
    }

    private String sha256(String content) {
        try {
            String normalized = content.replace("\r\n", "\n").trim();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}