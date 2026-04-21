package org.intermed.core.security;

import java.nio.file.Path;

/**
 * Fail-closed exception thrown when a mod attempts an operation outside its
 * strict security profile.
 */
public final class CapabilityDeniedException extends SecurityException {

    private final String subject;
    private final Capability capability;
    private final Path path;
    private final String host;
    private final String detail;
    private final String reason;

    public CapabilityDeniedException(String subject,
                                     SecurityPolicy.SecurityRequest request,
                                     String reason) {
        super(buildMessage(subject, request, reason));
        this.subject = subject;
        this.capability = request == null ? null : request.capability();
        this.path = request == null ? null : request.path();
        this.host = request == null ? null : request.host();
        this.detail = request == null ? null : request.detail();
        this.reason = reason == null || reason.isBlank()
            ? "operation is not allowed by the active strict security profile"
            : reason;
    }

    public String subject() {
        return subject;
    }

    public Capability capability() {
        return capability;
    }

    public Path path() {
        return path;
    }

    public String host() {
        return host;
    }

    public String detail() {
        return detail;
    }

    public String reason() {
        return reason;
    }

    private static String buildMessage(String subject,
                                       SecurityPolicy.SecurityRequest request,
                                       String reason) {
        Capability capability = request == null ? null : request.capability();
        return "CapabilityDeniedException: mod='" + printable(subject) + "'"
            + " capability=" + printable(capability)
            + " path=" + printable(request == null ? null : request.path())
            + " host=" + printable(request == null ? null : request.host())
            + " detail=" + printable(request == null ? null : request.detail())
            + " reason=" + printable(reason)
            + " action=" + remediation(capability);
    }

    private static String remediation(Capability capability) {
        if (capability == null) {
            return "review config/intermed-security-profiles.json and keep the operation blocked if unexpected";
        }
        return switch (capability) {
            case FILE_READ ->
                "grant FILE_READ with a scoped fileReadPaths entry, or keep blocked if the read was unexpected";
            case FILE_WRITE ->
                "grant FILE_WRITE with a scoped fileWritePaths entry, or keep blocked if the write was unexpected";
            case NETWORK_CONNECT ->
                "grant NETWORK_CONNECT with a scoped networkHosts entry, or keep blocked if the host is unexpected";
            case REFLECTION_ACCESS ->
                "grant REFLECTION_ACCESS only for trusted mods that legitimately need private reflection";
            case PROCESS_SPAWN ->
                "grant PROCESS_SPAWN only for trusted tooling mods; do not grant it to normal gameplay mods";
            case NATIVE_LIBRARY ->
                "grant NATIVE_LIBRARY only for trusted native-library mods and verify the library source";
            case MEMORY_ACCESS, UNSAFE_ACCESS ->
                "grant MEMORY_ACCESS only for trusted low-level mods and prefer scoped memoryMembers entries";
        };
    }

    private static String printable(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ').trim();
        return text.isBlank() ? "-" : text;
    }
}
