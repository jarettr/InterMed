package org.intermed.core.sandbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.api.InterMedAPI;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a lightweight WIT-like contract from methods explicitly exposed to sandboxes.
 *
 * <p>Only WIT-compatible methods are exported here. Espresso guests can still
 * call the wider Java API directly, but the raw Wasm host surface is limited to
 * primitive and {@link String}-based signatures that InterMed can lower
 * reliably.
 */
public final class WitContractCatalog {

    private static final String PACKAGE_NAME = "intermed:sandbox";
    private static final String INTERFACE_NAME = "host";
    private static final String DEFAULT_JAVA_BINDINGS_PACKAGE = "org.intermed.sandbox.guest";
    private static final String DEFAULT_JAVA_BINDINGS_CLASS = "InterMedSandboxHost";
    private static volatile List<HostMethod> cachedHostMethods;
    private static volatile List<WitFunction> cachedHostFunctions;
    private static volatile String cachedHostInterface;
    private static volatile String cachedJavaBindings;
    private static volatile String cachedContractDigest;

    private WitContractCatalog() {}

    public static List<WitFunction> hostFunctions() {
        List<WitFunction> cached = cachedHostFunctions;
        if (cached != null) {
            return cached;
        }
        synchronized (WitContractCatalog.class) {
            cached = cachedHostFunctions;
            if (cached != null) {
                return cached;
            }
            List<WitFunction> functions = new ArrayList<>();
            for (HostMethod hostMethod : hostMethods()) {
                Method method = hostMethod.method();
                List<WitParameter> parameters = new ArrayList<>();
                Class<?>[] parameterTypes = method.getParameterTypes();
                for (int i = 0; i < parameterTypes.length; i++) {
                    parameters.add(new WitParameter("arg" + i, mapType(parameterTypes[i])));
                }
                functions.add(new WitFunction(
                    hostMethod.name(),
                    parameters,
                    mapType(method.getReturnType()),
                    method.getDeclaringClass().getName()
                ));
            }
            functions.sort(Comparator.comparing(WitFunction::name));
            cached = List.copyOf(functions);
            cachedHostFunctions = cached;
            return cached;
        }
    }

    public static int hostExportCount() {
        return hostFunctions().size();
    }

    static String packageName() {
        return PACKAGE_NAME;
    }

    static String interfaceName() {
        return INTERFACE_NAME;
    }

    public static String defaultJavaBindingsClassName() {
        return DEFAULT_JAVA_BINDINGS_PACKAGE + "." + DEFAULT_JAVA_BINDINGS_CLASS;
    }

    static List<HostMethod> hostMethods() {
        List<HostMethod> cached = cachedHostMethods;
        if (cached != null) {
            return cached;
        }
        synchronized (WitContractCatalog.class) {
            cached = cachedHostMethods;
            if (cached != null) {
                return cached;
            }
            List<HostMethod> methods = new ArrayList<>();
            for (Method method : InterMedAPI.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!isExposed(method) || !isWitCompatible(method)) {
                    continue;
                }
                methods.add(new HostMethod(toWitName(method.getName()), method));
            }
            methods.sort(Comparator.comparing(HostMethod::name));
            cached = List.copyOf(methods);
            cachedHostMethods = cached;
            return cached;
        }
    }

    public static JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("package", PACKAGE_NAME);
        root.addProperty("interface", INTERFACE_NAME);
        root.addProperty("contractDigest", contractDigest());
        root.addProperty("exportCount", hostExportCount());
        root.addProperty("javaBindingsClass", defaultJavaBindingsClassName());
        root.addProperty("javaBindingsSha256", sha256(renderJavaBindings()));
        JsonArray functions = new JsonArray();
        for (WitFunction function : hostFunctions()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", function.name());
            entry.addProperty("returns", function.returns());
            entry.addProperty("owner", function.owner());
            JsonArray params = new JsonArray();
            for (WitParameter parameter : function.parameters()) {
                JsonObject paramJson = new JsonObject();
                paramJson.addProperty("name", parameter.name());
                paramJson.addProperty("type", parameter.type());
                params.add(paramJson);
            }
            entry.add("parameters", params);
            functions.add(entry);
        }
        root.add("functions", functions);
        root.addProperty("wit", renderHostInterface());
        return root;
    }

    public static String renderHostInterface() {
        String cached = cachedHostInterface;
        if (cached != null) {
            return cached;
        }
        synchronized (WitContractCatalog.class) {
            cached = cachedHostInterface;
            if (cached != null) {
                return cached;
            }
            StringBuilder builder = new StringBuilder();
            builder.append("package ").append(PACKAGE_NAME).append(";\n\n");
            builder.append("interface ").append(INTERFACE_NAME).append(" {\n");
            for (WitFunction function : hostFunctions()) {
                builder.append("  ").append(function.name()).append(": func(");
                for (int i = 0; i < function.parameters().size(); i++) {
                    WitParameter parameter = function.parameters().get(i);
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(parameter.name()).append(": ").append(parameter.type());
                }
                builder.append(")");
                if (!"unit".equals(function.returns())) {
                    builder.append(" -> ").append(function.returns());
                }
                builder.append(";\n");
            }
            builder.append("}\n");
            cached = builder.toString();
            cachedHostInterface = cached;
            return cached;
        }
    }

    public static String renderJavaBindings() {
        return renderJavaBindings(DEFAULT_JAVA_BINDINGS_PACKAGE, DEFAULT_JAVA_BINDINGS_CLASS);
    }

    public static String renderJavaBindings(String packageName, String className) {
        String normalizedPackage = sanitizeJavaIdentifier(packageName, DEFAULT_JAVA_BINDINGS_PACKAGE);
        String normalizedClassName = sanitizeSimpleJavaIdentifier(className, DEFAULT_JAVA_BINDINGS_CLASS);
        if (DEFAULT_JAVA_BINDINGS_PACKAGE.equals(normalizedPackage)
            && DEFAULT_JAVA_BINDINGS_CLASS.equals(normalizedClassName)) {
            String cached = cachedJavaBindings;
            if (cached != null) {
                return cached;
            }
            synchronized (WitContractCatalog.class) {
                cached = cachedJavaBindings;
                if (cached != null) {
                    return cached;
                }
                cached = renderJavaBindingsSource(normalizedPackage, normalizedClassName);
                cachedJavaBindings = cached;
                return cached;
            }
        }
        return renderJavaBindingsSource(normalizedPackage, normalizedClassName);
    }

    public static String contractDigest() {
        String cached = cachedContractDigest;
        if (cached != null) {
            return cached;
        }
        synchronized (WitContractCatalog.class) {
            cached = cachedContractDigest;
            if (cached != null) {
                return cached;
            }
            cached = sha256(renderHostInterface() + "\n// --- java-bindings ---\n" + renderJavaBindings());
            cachedContractDigest = cached;
            return cached;
        }
    }

    private static boolean isExposed(Method method) {
        return method.isAnnotationPresent(SandboxExposed.class)
            || method.getDeclaringClass().isAnnotationPresent(SandboxExposed.class);
    }

    private static boolean isWitCompatible(Method method) {
        if (!isSupportedType(method.getReturnType())) {
            return false;
        }
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (!isSupportedType(parameterType)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedType(Class<?> type) {
        return type == Void.TYPE
            || type == Void.class
            || type == boolean.class
            || type == Boolean.class
            || type == int.class
            || type == Integer.class
            || type == long.class
            || type == Long.class
            || type == float.class
            || type == Float.class
            || type == double.class
            || type == Double.class
            || type == String.class;
    }

    private static String toWitName(String javaName) {
        StringBuilder builder = new StringBuilder(javaName.length() + 4);
        for (int i = 0; i < javaName.length(); i++) {
            char c = javaName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (builder.length() > 0) {
                    builder.append('-');
                }
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static String mapType(Class<?> type) {
        if (type == Void.TYPE || type == Void.class) {
            return "unit";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "bool";
        }
        if (type == int.class || type == Integer.class) {
            return "s32";
        }
        if (type == long.class || type == Long.class) {
            return "s64";
        }
        if (type == float.class || type == Float.class) {
            return "float32";
        }
        if (type == double.class || type == Double.class) {
            return "float64";
        }
        return "string";
    }

    private static String renderJavaBindingsSource(String packageName, String className) {
        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("/**\n");
        builder.append(" * Generated InterMed sandbox host bindings.\n");
        builder.append(" * Contract digest: ").append(contractDigestSeed()).append('\n');
        builder.append(" */\n");
        builder.append("public final class ").append(className).append(" {\n");
        builder.append("  private ").append(className).append("() {}\n\n");
        for (HostMethod hostMethod : hostMethods()) {
            Method method = hostMethod.method();
            builder.append("  public static ")
                .append(javaTypeName(method.getReturnType()))
                .append(' ')
                .append(method.getName())
                .append('(');
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int index = 0; index < parameterTypes.length; index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append(javaTypeName(parameterTypes[index])).append(" arg").append(index);
            }
            builder.append(") {\n");
            builder.append("    ");
            if (method.getReturnType() != Void.TYPE && method.getReturnType() != Void.class) {
                builder.append("return ");
            }
            builder.append(InterMedAPI.class.getName())
                .append('.')
                .append(method.getName())
                .append('(');
            for (int index = 0; index < parameterTypes.length; index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append("arg").append(index);
            }
            builder.append(");\n");
            builder.append("  }\n\n");
        }
        builder.append("}\n");
        return builder.toString();
    }

    private static String contractDigestSeed() {
        return sha256(renderHostInterface());
    }

    private static String javaTypeName(Class<?> type) {
        if (type == null) {
            return "void";
        }
        if (type.isPrimitive()) {
            return type.getName();
        }
        if (type == String.class) {
            return "String";
        }
        return type.getCanonicalName();
    }

    private static String sanitizeJavaIdentifier(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        String[] parts = candidate.trim().split("\\.");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(sanitizeSimpleJavaIdentifier(part, "pkg"));
        }
        return builder.length() == 0 ? fallback : builder.toString();
    }

    private static String sanitizeSimpleJavaIdentifier(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        String trimmed = candidate.trim();
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char c = trimmed.charAt(index);
            if ((index == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c))) {
                builder.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                if (index == 0) {
                    builder.append('_');
                }
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.length() == 0 ? fallback : builder.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute WIT contract digest", e);
        }
    }

    public record WitFunction(String name, List<WitParameter> parameters, String returns, String owner) {}

    public record WitParameter(String name, String type) {}

    record HostMethod(String name, Method method) {}
}
