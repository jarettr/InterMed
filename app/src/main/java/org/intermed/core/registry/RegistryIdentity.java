package org.intermed.core.registry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of a stable registry identity from registry receiver
 * objects seen by the runtime hooks.
 *
 * <p>The production runtime spans multiple ecosystems and versions, so we avoid
 * linking against concrete Minecraft/Forge/NeoForge registry types here. The
 * extractor prefers reflective accessors such as {@code getRegistryKey()},
 * {@code key()}, and {@code location()}, then falls back to parsing
 * namespaced IDs from {@code toString()} output.
 */
public final class RegistryIdentity {

    private static final Pattern RESOURCE_LOCATION =
        Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT_IDENTITY =
        Pattern.compile("^[\\w.$]+@[0-9a-f]+$", Pattern.CASE_INSENSITIVE);

    private static final String[] ACCESSORS = {
        "getRegistryKey",
        "registryKey",
        "getKey",
        "key",
        "getRegistryName",
        "registryName",
        "getLocation",
        "location",
        "name"
    };

    private static final String[] FIELDS = {
        "registryKey",
        "key",
        "location",
        "registryName",
        "name"
    };

    private RegistryIdentity() {}

    public static String scopeOf(Object registryCandidate) {
        String resolved = resolve(registryCandidate, 0, new IdentityHashMap<>());
        if (resolved == null || resolved.isBlank()) {
            return "";
        }
        return resolved.trim().toLowerCase(Locale.ROOT);
    }

    public static String scopeOfLookup(Object receiver, Object[] args) {
        String receiverScope = scopeOf(receiver);
        if (!receiverScope.isBlank()) {
            return receiverScope;
        }

        if (args == null || args.length <= 1) {
            return "";
        }

        for (int index = 0; index < args.length - 1; index++) {
            String contextScope = scopeOf(args[index]);
            if (!contextScope.isBlank()) {
                return contextScope;
            }
        }

        return "";
    }

    private static String resolve(Object candidate,
                                  int depth,
                                  Map<Object, Boolean> visited) {
        if (candidate == null || depth > 4 || visited.put(candidate, Boolean.TRUE) != null) {
            return null;
        }

        if (candidate instanceof CharSequence chars) {
            return normalizeText(chars.toString());
        }

        if (candidate instanceof Enum<?> enumValue) {
            return normalizeText(enumValue.name());
        }

        for (String accessor : ACCESSORS) {
            Object nested = invokeNoArg(candidate, accessor);
            String resolved = resolve(nested, depth + 1, visited);
            if (resolved != null) {
                return resolved;
            }
        }

        for (String fieldName : FIELDS) {
            Object nested = readField(candidate, fieldName);
            String resolved = resolve(nested, depth + 1, visited);
            if (resolved != null) {
                return resolved;
            }
        }

        return normalizeText(String.valueOf(candidate));
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        Matcher matcher = RESOURCE_LOCATION.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (OBJECT_IDENTITY.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
