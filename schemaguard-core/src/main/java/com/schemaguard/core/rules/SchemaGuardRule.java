package com.schemaguard.core.rules;

import java.util.List;

public interface SchemaGuardRule {
    String id();
    List<Finding> evaluate(AnalysisContext context);
}