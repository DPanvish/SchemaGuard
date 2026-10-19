package com.schemaguard.core.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private final List<SchemaGuardRule> rules;

    public RuleEngine(List<SchemaGuardRule> rules) {
        this.rules = rules;
    }

    /**
     * Evaluates all enabled rules against the immutable AnalysisContext and sorts findings deterministically.
     */
    public List<Finding> evaluateAll(AnalysisContext context) {
        return evaluate(context, null);
    }

    public List<Finding> evaluate(AnalysisContext context, Set<String> enabledRuleIds) {
        log.info("Starting risk rule evaluation across {} registered rule(s)", rules.size());
        List<Finding> allFindings = new ArrayList<>();

        for (SchemaGuardRule rule : rules) {
            if (enabledRuleIds != null && !enabledRuleIds.contains(rule.id())) {
                log.debug("Skipping disabled rule: {}", rule.id());
                continue;
            }
            List<Finding> ruleFindings = rule.evaluate(context);
            if (!ruleFindings.isEmpty()) {
                log.info("Rule {} produced {} finding(s)", rule.id(), ruleFindings.size());
                allFindings.addAll(ruleFindings);
            }
        }

        Collections.sort(allFindings);
        log.info("Completed risk rule evaluation: {} total finding(s)", allFindings.size());
        return Collections.unmodifiableList(allFindings);
    }
}