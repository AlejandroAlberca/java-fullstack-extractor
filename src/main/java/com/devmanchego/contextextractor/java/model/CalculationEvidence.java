package com.devmanchego.contextextractor.java.model;

import java.util.List;
import java.util.Objects;

/**
 * Evidence that a field is calculated: where, how, and with what confidence.
 * Multiple evidences can exist for the same field (double-calculation pattern).
 */
public final class CalculationEvidence {

    /**
     * Where the calculation occurs (locus).
     */
    public enum Locus {
        // Backend
        BACKEND_FORMULA_HIBERNATE,    // @Formula("SQL")
        BACKEND_MAPSTRUCT_EXPRESSION, // @Mapping(target=X, expression="java(...)")
        BACKEND_MAPSTRUCT_CONSTANT,   // @Mapping(target=X, constant="value")
        BACKEND_MAPSTRUCT_DEFAULT,    // @Mapping(target=X, defaultValue="value")
        BACKEND_GETTER_TRANSIENT,     // @Transient getter method
        BACKEND_GETTER_COMPUTED,      // plain getter with calculation (Tier 2)
        BACKEND_PRE_PERSIST,          // @PrePersist method
        BACKEND_PRE_UPDATE,           // @PreUpdate method
        BACKEND_SERVICE_SETTER,       // target.setField(EXPR) in service layer (Tier 2)
        BACKEND_SERVICE_ASSIGNMENT,   // this.field = EXPR / field = EXPR (Tier 2)
        BACKEND_ACCUMULATOR_SLICE,    // path-dependent accumulator, cited verbatim (Tier 3b)
        BACKEND_DTO_FIELD,            // DTO record field with calculated type hint

        // Frontend
        FRONTEND_SIGNAL_COMPUTED,     // computed(() => ...)
        FRONTEND_GETTER,              // get propertyName() { return ...; }
        FRONTEND_METHOD_SLICE,        // multi-statement method/getter body, cited verbatim (Tier 3)
        FRONTEND_PIPE,                // | pipeName
        FRONTEND_INTERPOLATION,       // {{ a * b }}
        FRONTEND_FORM_BINDING,        // formControlName binding with calculation
        FRONTEND_RXJS_VALUECHANAGES,  // .pipe(map(...))

        // Generic
        UNKNOWN
    }

    /**
     * Confidence in this evidence.
     */
    public enum Confidence {
        HIGH,     // Explicit @Mapping, @Formula, computed(), pipe — literal in code
        MEDIUM,   // Implicit getter, interpolation, form binding — inferred from pattern
        LOW,      // Best-effort, requires manual verification
        UNKNOWN
    }

    /**
     * Shape of the evidence's {@code expression}: a single reconstructed formula, or a
     * verbatim source-code slice cited because no single formula exists (path-dependent
     * accumulator, multi-statement method).
     */
    public enum Kind {
        FORMULA,
        SLICE
    }

    private final String targetField;         // Field being calculated (e.g., "annualSalary")
    private final Locus locus;                // Where: @Formula, expression=, getter, etc.
    private final String expression;          // The formula/literal (null if only puntero)
    private final List<String> inputFields;   // Source fields referenced
    private final String sourceFile;          // File path
    private final String sourceClass;         // Class containing the calculation
    private final String sourceMethod;        // Method name (if applicable)
    private final int lineNumber;             // Line number in source
    private final Confidence confidence;      // How confident we are
    private final int tier;                   // 1a, 1b, 2, 3+
    private final String description;         // Human-readable summary
    private final Kind kind;                  // FORMULA (expression) or SLICE (cited code)
    private final String language;            // "java" / "ts" — fence language when kind=SLICE

    private CalculationEvidence(Builder b) {
        this.targetField = b.targetField;
        this.locus = b.locus;
        this.expression = b.expression;
        this.inputFields = List.copyOf(b.inputFields);
        this.sourceFile = b.sourceFile;
        this.sourceClass = b.sourceClass;
        this.sourceMethod = b.sourceMethod;
        this.lineNumber = b.lineNumber;
        this.confidence = b.confidence;
        this.tier = b.tier;
        this.description = b.description;
        this.kind = b.kind;
        this.language = b.language;
    }

    // Getters
    public String getTargetField() { return targetField; }
    public Locus getLocus() { return locus; }
    public String getExpression() { return expression; }
    public List<String> getInputFields() { return inputFields; }
    public String getSourceFile() { return sourceFile; }
    public String getSourceClass() { return sourceClass; }
    public String getSourceMethod() { return sourceMethod; }
    public int getLineNumber() { return lineNumber; }
    public Confidence getConfidence() { return confidence; }
    public int getTier() { return tier; }
    public String getDescription() { return description; }
    public Kind getKind() { return kind; }
    public String getLanguage() { return language; }

    /**
     * Short human-readable locator for display.
     */
    public String getLocusLabel() {
        return switch (locus) {
            case BACKEND_FORMULA_HIBERNATE -> "@Formula (Hibernate SQL)";
            case BACKEND_MAPSTRUCT_EXPRESSION -> "@Mapping expression=";
            case BACKEND_MAPSTRUCT_CONSTANT -> "@Mapping constant=";
            case BACKEND_MAPSTRUCT_DEFAULT -> "@Mapping defaultValue=";
            case BACKEND_GETTER_TRANSIENT -> "@Transient getter";
            case BACKEND_GETTER_COMPUTED -> "computed getter";
            case BACKEND_PRE_PERSIST -> "@PrePersist";
            case BACKEND_PRE_UPDATE -> "@PreUpdate";
            case BACKEND_SERVICE_SETTER -> "service setter";
            case BACKEND_SERVICE_ASSIGNMENT -> "service assignment";
            case BACKEND_ACCUMULATOR_SLICE -> "path-dependent accumulator";
            case BACKEND_DTO_FIELD -> "DTO record field";
            case FRONTEND_SIGNAL_COMPUTED -> "Signal computed()";
            case FRONTEND_GETTER -> "Component getter";
            case FRONTEND_METHOD_SLICE -> "multi-statement method";
            case FRONTEND_PIPE -> "Angular pipe";
            case FRONTEND_INTERPOLATION -> "Template interpolation {{ }}";
            case FRONTEND_FORM_BINDING -> "Form binding calculation";
            case FRONTEND_RXJS_VALUECHANAGES -> "RxJS valueChanges";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * Indicates this is Tier 1 (literal).
     */
    public boolean isTier1() {
        return tier == 1;
    }

    /**
     * Whether this evidence represents a genuine value computation (as opposed to a weak
     * name heuristic or a display-only transform). Used to gate the CALCULATED upgrade and
     * the backend/frontend double-calculation check.
     */
    public boolean isValueComputation() {
        return locus != Locus.BACKEND_DTO_FIELD
                && locus != Locus.FRONTEND_PIPE
                && locus != Locus.FRONTEND_INTERPOLATION
                && expression != null && !expression.isBlank();
    }

    /**
     * Coarse layer of this evidence: BACKEND or FRONTEND.
     */
    public String layer() {
        return locus.name().startsWith("FRONTEND") ? "FRONTEND" : "BACKEND";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CalculationEvidence that)) return false;
        return Objects.equals(targetField, that.targetField) &&
               locus == that.locus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetField, locus);
    }

    @Override
    public String toString() {
        return String.format("%s (via %s, confidence=%s, inputs=%s)",
                targetField, getLocusLabel(), confidence, inputFields);
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String targetField;
        private Locus locus = Locus.UNKNOWN;
        private String expression;
        private final List<String> inputFields = new java.util.ArrayList<>();
        private String sourceFile;
        private String sourceClass;
        private String sourceMethod;
        private int lineNumber = -1;
        private Confidence confidence = Confidence.UNKNOWN;
        private int tier = 1;
        private String description;
        private Kind kind = Kind.FORMULA;
        private String language = "java";

        public Builder targetField(String v) { this.targetField = v; return this; }
        public Builder locus(Locus v) { this.locus = v; return this; }
        public Builder expression(String v) { this.expression = v; return this; }
        public Builder inputField(String v) { this.inputFields.add(v); return this; }
        public Builder inputFields(java.util.Collection<String> v) { this.inputFields.addAll(v); return this; }
        public Builder sourceFile(String v) { this.sourceFile = v; return this; }
        public Builder sourceClass(String v) { this.sourceClass = v; return this; }
        public Builder sourceMethod(String v) { this.sourceMethod = v; return this; }
        public Builder lineNumber(int v) { this.lineNumber = v; return this; }
        public Builder confidence(Confidence v) { this.confidence = v; return this; }
        public Builder tier(int v) { this.tier = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder kind(Kind v) { this.kind = v; return this; }
        public Builder language(String v) { this.language = v; return this; }

        public CalculationEvidence build() {
            if (targetField == null) throw new IllegalStateException("targetField is required");
            if (sourceClass == null) throw new IllegalStateException("sourceClass is required");
            return new CalculationEvidence(this);
        }
    }
}
