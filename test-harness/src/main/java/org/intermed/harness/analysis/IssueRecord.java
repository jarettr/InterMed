package org.intermed.harness.analysis;

/**
 * A single issue detected in the server output log during a test run.
 */
public record IssueRecord(
    /** Classification of this issue. */
    Severity severity,
    /** Short machine-readable tag (e.g. "MIXIN_CONFLICT", "CAPABILITY_DENIED"). */
    String tag,
    /** Human-readable description. */
    String description,
    /** The log line(s) that triggered this record. */
    String evidence
) {
    public enum Severity {
        /** Informational — does not affect outcome classification. */
        INFO,
        /** Performance or compatibility warning. */
        WARN,
        /** A recoverable error (e.g. bridged Mixin conflict). */
        ERROR,
        /** Fatal — caused the server to crash or fail to start. */
        FATAL
    }

    public boolean isFatal()   { return severity == Severity.FATAL; }
    public boolean isError()   { return severity == Severity.ERROR || isFatal(); }

    @Override public String toString() {
        return "[" + severity + "/" + tag + "] " + description;
    }
}
