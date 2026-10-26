package com.schemaguard.core.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.schemaguard.core.policy.DecisionOutcome;
import com.schemaguard.core.rules.Finding;
import com.schemaguard.core.rules.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);
    private final ObjectMapper jsonMapper;

    public ReportGenerator() {
        this.jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Renders a human-readable Markdown report matching Appendix A of the project blueprint.
     */
    public String toMarkdown(AnalysisReport report) {
        log.debug("Rendering Markdown report for runId={}", report.runId());
        StringBuilder md = new StringBuilder();

        String bannerStatus = (report.decision() == DecisionOutcome.PASS || report.decision() == DecisionOutcome.WARN)
                ? "POLICY CHECK PASSED (" + report.decision() + ")"
                : "POLICY CHECK FAILED (" + report.decision() + ")";

        md.append("# SCHEMAGUARD: ").append(bannerStatus).append("\n\n");

        if (!report.findings().isEmpty()) {
            Finding primary = report.findings().get(0);
            md.append("> **Rule ").append(primary.ruleId()).append(" — ").append(primary.title()).append(".** ")
              .append(primary.message()).append(".\n\n");
        }

        md.append("| Field | Value |\n");
        md.append("| :--- | :--- |\n");
        md.append("| **Run ID** | `").append(report.runId()).append("` |\n");
        md.append("| **Base / candidate** | `").append(report.baseRef()).append("` → `").append(report.headRef()).append("` |\n");

        if (!report.findings().isEmpty()) {
            Finding primary = report.findings().get(0);
            md.append("| **Migration** | `").append(primary.sourceLocation().formatted()).append("` |\n");
            md.append("| **Affected object** | `").append(primary.objectRef()).append("` |\n");
            md.append("| **Rule / severity** | `").append(primary.ruleId()).append(" / ").append(primary.severity()).append("` |\n");
            md.append("| **Evidence** | ").append(String.join("; ", primary.evidence())).append(" |\n");
        }

        md.append("| **Sandbox status** | ").append(report.sandboxSummary()).append(" |\n");
        md.append("| **Decision** | **").append(report.decision()).append("** (Exit Code ").append(report.exitCode()).append(") |\n\n");

        if (!report.findings().isEmpty()) {
            md.append("## Detailed Findings (").append(report.findings().size()).append(")\n\n");
            for (Finding f : report.findings()) {
                md.append("### [").append(f.ruleId()).append("] ").append(f.title())
                  .append(" (").append(f.severity()).append(")\n");
                md.append("- **Object:** `").append(f.objectRef()).append("`\n");
                md.append("- **Source:** `").append(f.sourceLocation().formatted()).append("`\n");
                md.append("- **Confidence:** `").append(f.confidenceMarker()).append("`\n");
                md.append("- **Evidence:**\n");
                for (String ev : f.evidence()) {
                    md.append("  - ").append(ev).append("\n");
                }
                md.append("- **Recommended Remediation:**\n\n");
                md.append("```text\n").append(f.remediation()).append("\n```\n\n");
            }
        }

        return md.toString();
    }

    /**
     * Renders a stable, versioned machine-readable JSON report.
     */
    public String toJson(AnalysisReport report) {
        log.debug("Rendering JSON report for runId={}", report.runId());
        try {
            return jsonMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AnalysisReport to JSON", e);
            throw new IllegalStateException("Unable to serialize JSON report", e);
        }
    }

    /**
     * Renders SARIF 2.1.0 compliant with GitHub Code Scanning requirements (Section 8.5 & Ref [6]).
     */
    public String toSarif(AnalysisReport report) {
        log.debug("Rendering SARIF 2.1.0 report for runId={}", report.runId());
        Map<String, Object> sarifRoot = new LinkedHashMap<>();
        sarifRoot.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        sarifRoot.put("version", "2.1.0");

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "SchemaGuard");
        driver.put("version", report.toolVersion());
        driver.put("informationUri", "https://github.com/schemaguard/schemaguard");

        Map<String, Map<String, Object>> rulesMap = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (Finding f : report.findings()) {
            rulesMap.putIfAbsent(f.ruleId(), Map.of(
                    "id", f.ruleId(),
                    "name", f.title().replaceAll("\\s+", ""),
                    "shortDescription", Map.of("text", f.title()),
                    "fullDescription", Map.of("text", f.message()),
                    "help", Map.of("text", f.remediation())
            ));

            String fileUri = (f.sourceLocation() != null && f.sourceLocation().file() != null)
                    ? f.sourceLocation().file() : "db/migration/unknown.sql";
            int line = (f.sourceLocation() != null && f.sourceLocation().line() != null && f.sourceLocation().line() > 0)
                    ? f.sourceLocation().line() : 1;

            Map<String, Object> physicalLocation = Map.of(
                    "artifactLocation", Map.of("uri", fileUri),
                    "region", Map.of("startLine", line)
            );

            Map<String, Object> resultEntry = new LinkedHashMap<>();
            resultEntry.put("ruleId", f.ruleId());
            resultEntry.put("level", mapSarifLevel(f.severity()));
            resultEntry.put("message", Map.of("text", f.message() + " | Evidence: " + String.join("; ", f.evidence())));
            resultEntry.put("locations", List.of(Map.of("physicalLocation", physicalLocation)));

            results.add(resultEntry);
        }

        driver.put("rules", new ArrayList<>(rulesMap.values()));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", results);

        sarifRoot.put("runs", List.of(run));

        try {
            return jsonMapper.writeValueAsString(sarifRoot);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SARIF 2.1.0 report", e);
            throw new IllegalStateException("Unable to serialize SARIF report", e);
        }
    }

    private String mapSarifLevel(Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case REVIEW_REQUIRED, MEDIUM, WARN -> "warning";
            case LOW -> "note";
        };
    }
}