package com.devmanchego.contextextractor.java.model;

/** A method annotated with {@code @Scheduled} found in the Java backend. */
public final class ScheduledJobInfo {

    private final String className;
    private final String methodName;
    private final String sourceFile;
    private final String cron;          // null when not set
    private final String fixedRate;     // null when not set (ms or ISO-8601 duration)
    private final String fixedDelay;    // null when not set
    private final String initialDelay;  // null when not set

    public ScheduledJobInfo(String className, String methodName, String sourceFile,
                            String cron, String fixedRate, String fixedDelay, String initialDelay) {
        this.className    = className;
        this.methodName   = methodName;
        this.sourceFile   = sourceFile;
        this.cron         = cron;
        this.fixedRate    = fixedRate;
        this.fixedDelay   = fixedDelay;
        this.initialDelay = initialDelay;
    }

    public String getClassName()   { return className; }
    public String getMethodName()  { return methodName; }
    public String getSourceFile()  { return sourceFile; }
    public String getCron()        { return cron; }
    public String getFixedRate()   { return fixedRate; }
    public String getFixedDelay()  { return fixedDelay; }
    public String getInitialDelay(){ return initialDelay; }

    /** Human-readable schedule summary for display. */
    public String scheduleDescription() {
        if (cron != null)        return "cron = \"" + cron + "\"";
        if (fixedRate != null)   return "fixedRate = " + fixedRate;
        if (fixedDelay != null)  return "fixedDelay = " + fixedDelay;
        return "(no schedule attributes)";
    }
}
