package com.schemaguard.core.rules;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record Finding(
    String ruleId,
    Severity severity,
    String title,
    String message,
    String objectRef,
    SourceLocation sourceLocation,
    List<String> evidence,
    String remediation,
    String confidenceMarker
) implements Comparable<Finding> {

    public record SourceLocation(
        String file,
        Integer line
    ) {
        public String formatted() {
            if (file == null || file.isBlank()) {
                return "unknown";
            }
            return line != null ? file + ":" + line : file;
        }
    }

    private static final Comparator<Finding> DETERMINISTIC_COMPARATOR = Comparator
            .comparingInt((Finding f) -> f.severity().rank())
            .thenComparing(f -> Objects.toString(f.ruleId(), ""))
            .thenComparing(f -> Objects.toString(f.objectRef(), ""))
            .thenComparing(f -> f.sourceLocation() != null ? Objects.toString(f.sourceLocation().file(), "") : "")
            .thenComparingInt(f -> (f.sourceLocation() != null && f.sourceLocation().line() != null) ? f.sourceLocation().line() : 0);

    @Override
    public int compareTo(Finding other) {
        return DETERMINISTIC_COMPARATOR.compare(this, other);
    }
}