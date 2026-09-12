package com.devmanchego.contextextractor.callgraph;

/**
 * A business-relevant branching condition extracted from a method body.
 * Filtered by {@link ConditionExtractor} to retain only semantically significant
 * logic (service calls, domain enums, return-controlling ifs, domain exceptions).
 */
public final class FlowCondition {

    private final String conditionText;
    private final String thenSummary;
    private final String elseSummary;
    private final boolean hasElse;

    public FlowCondition(String conditionText, String thenSummary,
                         String elseSummary, boolean hasElse) {
        this.conditionText = conditionText;
        this.thenSummary   = thenSummary;
        this.elseSummary   = elseSummary;
        this.hasElse       = hasElse;
    }

    public String getConditionText() { return conditionText; }
    public String getThenSummary()   { return thenSummary; }
    public String getElseSummary()   { return elseSummary; }
    public boolean isHasElse()       { return hasElse; }
}
