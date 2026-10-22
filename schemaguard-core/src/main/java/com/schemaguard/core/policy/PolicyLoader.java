package com.schemaguard.core.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.schemaguard.core.policy.PolicyManifest.RuleOverride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PolicyLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicyLoader.class);

    private static final Set<String> VALID_RULE_IDS = Set.of(
            "SG001", "SG002", "SG003", "SG004", "SG005", "SG006",
            "SG007", "SG008", "SG009", "SG010", "SG011", "SG012"
    );
    private static final Set<String> VALID_ACTIONS = Set.of("block", "warn", "require-review", "allow");
    private static final Set<String> VALID_FORMATS = Set.of("markdown", "json", "sarif");

    private final ObjectMapper strictYamlMapper;

    public PolicyLoader() {
        this.strictYamlMapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    }

    public PolicyManifest loadAndValidate(File policyFile) {
        log.info("Loading policy manifest from file: {}", policyFile.getName());
        try {
            String content = Files.readString(policyFile.toPath(), StandardCharsets.UTF_8);
            return loadAndValidateFromString(policyFile.getName(), content);
        } catch (IOException e) {
            log.error("Failed to read policy file: {}", policyFile.getName(), e);
            throw new IllegalArgumentException("Cannot read policy file: " + policyFile.getName(), e);
        }
    }

    public PolicyManifest loadAndValidateFromString(String sourceName, String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("Policy file is empty: " + sourceName);
        }
        try {
            PolicyManifest manifest = strictYamlMapper.readValue(yamlContent, PolicyManifest.class);
            validateSemantics(sourceName, manifest);
            log.info("Validated policy manifest {} [version={}, dialect={}]",
                    sourceName, manifest.version(), manifest.dialect());
            return manifest;
        } catch (IOException e) {
            log.error("Invalid policy YAML in {}: {}", sourceName, e.getMessage());
            throw new IllegalArgumentException("Invalid policy YAML in " + sourceName + ": " + e.getMessage(), e);
        }
    }

    private void validateSemantics(String sourceName, PolicyManifest manifest) {
        if (manifest.version() != 1) {
            throw new IllegalArgumentException("Unsupported policy version " + manifest.version() + " in " + sourceName);
        }
        if (manifest.dialect() == null || !"postgresql".equalsIgnoreCase(manifest.dialect().trim())) {
            throw new IllegalArgumentException("Unsupported dialect '" + manifest.dialect() + "' in " + sourceName + ". Only 'postgresql' is supported in v1.");
        }
        if (manifest.policy() == null) {
            throw new IllegalArgumentException("Missing 'policy' block in " + sourceName);
        }
        validateAction("unsupportedSql", manifest.policy().unsupportedSql(), sourceName);
        validateAction("destructiveChanges", manifest.policy().destructiveChanges(), sourceName);
        validateAction("breakingContracts", manifest.policy().breakingContracts(), sourceName);
        validateAction("migrationHistoryChanges", manifest.policy().migrationHistoryChanges(), sourceName);

        if (manifest.rules() != null) {
            for (Map.Entry<String, RuleOverride> entry : manifest.rules().entrySet()) {
                if (!VALID_RULE_IDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Unknown rule ID '" + entry.getKey() + "' in " + sourceName);
                }
                if (entry.getValue() == null || entry.getValue().severity() == null) {
                    throw new IllegalArgumentException("Rule '" + entry.getKey() + "' must specify a valid severity in " + sourceName);
                }
            }
        }

        if (manifest.reports() == null || manifest.reports().formats() == null || manifest.reports().formats().isEmpty()) {
            throw new IllegalArgumentException("Policy must specify at least one report format in " + sourceName);
        }
        for (String fmt : manifest.reports().formats()) {
            if (fmt == null || !VALID_FORMATS.contains(fmt.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported report format '" + fmt + "' in " + sourceName);
            }
        }
    }

    private void validateAction(String fieldName, String value, String sourceName) {
        if (value == null || !VALID_ACTIONS.contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid policy action '" + value + "' for " + fieldName + " in " + sourceName);
        }
    }
}