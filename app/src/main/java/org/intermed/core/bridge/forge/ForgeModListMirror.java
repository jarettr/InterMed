package org.intermed.core.bridge.forge;

import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ForgeModListMirror {
    private static final String INTERMED_UI_MARK = " [I]";
    private static final String INTERMED_DISCOVERED_UI_MARK = " [I?]";
    private static final String INTERMED_REPORT_MARK = "[I] ";
    private static volatile boolean loggedMirror;
    private static volatile boolean loggedScreenRebuild;
    private static final ThreadLocal<Boolean> SCREEN_SYNC_GUARD = ThreadLocal.withInitial(() -> false);

    private ForgeModListMirror() {}

    public static List<?> augmentModInfos(List<?> forgeMods) {
        if (!Boolean.parseBoolean(System.getProperty("intermed.forge.modlist.mirror", "true"))
            || forgeMods == null
            || RuntimeModIndex.visibleModsForUi().isEmpty()) {
            return forgeMods;
        }
        try {
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
            ClassLoader classLoader = null;
            Object borrowedFileInfo = null;
            for (Object mod : forgeMods) {
                if (mod == null) {
                    continue;
                }
                if (classLoader == null) {
                    classLoader = mod.getClass().getClassLoader();
                }
                if (borrowedFileInfo == null) {
                    borrowedFileInfo = readOwningFile(mod);
                }
                merged.putIfAbsent(readModId(mod), mod);
            }
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
            }
            MirrorTypes types = MirrorTypes.load(classLoader);
            for (NormalizedModMetadata metadata : RuntimeModIndex.visibleModsForUi().stream()
                .sorted(java.util.Comparator.comparing(NormalizedModMetadata::id))
                .toList()) {
                if (shouldMirror(metadata)) {
                    merged.putIfAbsent(metadata.id(), createModInfo(types, metadata, borrowedFileInfo));
                }
            }
            List<Object> augmented = new ArrayList<>(merged.values());
            if (!loggedMirror && augmented.size() != forgeMods.size()) {
                loggedMirror = true;
                System.out.printf("[InterMed Forge ModList] Mirroring %d InterMed mod candidate(s) into Forge ModList UI with %s marker.%n",
                    augmented.size() - forgeMods.size(),
                    "I");
            }
            return augmented;
        } catch (Throwable t) {
            System.err.println("[InterMed Forge ModList] Mirror failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return forgeMods;
        }
    }

    public static void syncScreenLists(Object screen) {
        if (!Boolean.parseBoolean(System.getProperty("intermed.forge.modlist.mirror", "true"))
            || screen == null
            || RuntimeModIndex.visibleModsForUi().isEmpty()
            || Boolean.TRUE.equals(SCREEN_SYNC_GUARD.get())) {
            return;
        }
        try {
            SCREEN_SYNC_GUARD.set(true);
            Object modsValue = readField(screen, "mods");
            if (!(modsValue instanceof List<?> mods)) {
                return;
            }
            Object unsortedValue = readField(screen, "unsortedMods");
            List<?> unsortedMods = unsortedValue instanceof Collection<?> unsorted
                ? new ArrayList<>(unsorted)
                : new ArrayList<>(mods);

            List<?> augmentedMods = augmentModInfos(mods);
            List<?> augmentedUnsorted = augmentModInfos(unsortedMods);
            boolean modsChanged = !sameModIds(mods, augmentedMods);
            boolean unsortedChanged = !sameModIds(unsortedMods, augmentedUnsorted);
            if (!modsChanged && !unsortedChanged) {
                return;
            }
            if (modsChanged) {
                writeField(screen, "mods", new ArrayList<>(augmentedMods));
            }
            if (unsortedChanged) {
                writeField(screen, "unsortedMods", List.copyOf(augmentedUnsorted));
            }
            rebuildScreenState(screen);
            if (!loggedScreenRebuild) {
                loggedScreenRebuild = true;
                System.out.printf("[InterMed Forge ModList] Rebuilt ModListScreen with %d visible InterMed candidate(s).%n",
                    countMirroredCandidates(augmentedUnsorted));
            }
        } catch (Throwable t) {
            System.err.println("[InterMed Forge ModList] Screen sync failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            SCREEN_SYNC_GUARD.remove();
        }
    }

    public static String augmentCrashReport(String report) {
        if (report == null || RuntimeModIndex.visibleModsForUi().isEmpty() || report.contains("InterMed mirrored Fabric mods:")) {
            return report;
        }
        StringBuilder builder = new StringBuilder(report);
        builder.append(System.lineSeparator()).append("InterMed mirrored Fabric mods:");
        RuntimeModIndex.visibleModsForUi().stream()
            .sorted(java.util.Comparator.comparing(NormalizedModMetadata::id))
            .forEach(metadata -> builder
                .append(System.lineSeparator())
                .append("\t")
                .append(INTERMED_REPORT_MARK)
                .append(metadata.id())
                .append(" | ")
                .append(metadata.name())
                .append(" | ")
                .append(metadata.version()));
        return builder.toString();
    }

    private static boolean shouldMirror(NormalizedModMetadata metadata) {
        if (metadata.platform() != ModPlatform.FABRIC) {
            return false;
        }
        String id = metadata.id();
        if (id == null || id.isBlank()) {
            return false;
        }
        if (id.startsWith("fabric-") && !"fabric-api".equals(id)) {
            return false;
        }
        return !id.equals("cloth-basic-math")
            && !id.equals("mixinextras")
            && !id.startsWith("org_");
    }

    private static Object createModInfo(MirrorTypes types,
                                        NormalizedModMetadata metadata,
                                        Object borrowedFileInfo) throws Exception {
        MirrorState state = new MirrorState(types, metadata, borrowedFileInfo);
        state.fileInfo = Proxy.newProxyInstance(
            types.classLoader,
            new Class<?>[] { types.modFileInfo, types.configurable },
            state::invokeFileInfo
        );
        state.modFile = Proxy.newProxyInstance(
            types.classLoader,
            new Class<?>[] { types.modFile },
            state::invokeModFile
        );
        state.provider = Proxy.newProxyInstance(
            types.classLoader,
            new Class<?>[] { types.modProvider },
            state::invokeProvider
        );
        state.modInfo = Proxy.newProxyInstance(
            types.classLoader,
            new Class<?>[] { types.modInfo, types.configurable },
            state::invokeModInfo
        );
        return state.modInfo;
    }

    private static String readModId(Object mod) {
        try {
            Method method = mod.getClass().getMethod("getModId");
            Object value = method.invoke(mod);
            return String.valueOf(value);
        } catch (Exception ignored) {
            return String.valueOf(mod);
        }
    }

    private static Object readOwningFile(Object mod) {
        try {
            Method method = mod.getClass().getMethod("getOwningFile");
            return method.invoke(mod);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record MirrorTypes(ClassLoader classLoader,
                               Class<?> modInfo,
                               Class<?> modFileInfo,
                               Class<?> configurable,
                               Class<?> modFile,
                               Class<?> modProvider,
                               Class<?> modFileType,
                               Class<?> defaultArtifactVersion,
                               Class<?> modFileScanData) {
        static MirrorTypes load(ClassLoader loader) throws ClassNotFoundException {
            ClassLoader effective = loader == null ? ForgeModListMirror.class.getClassLoader() : loader;
            return new MirrorTypes(
                effective,
                Class.forName("net.minecraftforge.forgespi.language.IModInfo", false, effective),
                Class.forName("net.minecraftforge.forgespi.language.IModFileInfo", false, effective),
                Class.forName("net.minecraftforge.forgespi.language.IConfigurable", false, effective),
                Class.forName("net.minecraftforge.forgespi.locating.IModFile", false, effective),
                Class.forName("net.minecraftforge.forgespi.locating.IModProvider", false, effective),
                Class.forName("net.minecraftforge.forgespi.locating.IModFile$Type", false, effective),
                Class.forName("org.apache.maven.artifact.versioning.DefaultArtifactVersion", false, effective),
                Class.forName("net.minecraftforge.forgespi.language.ModFileScanData", false, effective)
            );
        }
    }

    private static final class MirrorState {
        private final MirrorTypes types;
        private final NormalizedModMetadata metadata;
        private final Object borrowedFileInfo;
        private Object modInfo;
        private Object fileInfo;
        private Object modFile;
        private Object provider;

        private MirrorState(MirrorTypes types, NormalizedModMetadata metadata, Object borrowedFileInfo) {
            this.types = types;
            this.metadata = metadata;
            this.borrowedFileInfo = borrowedFileInfo;
        }

        private Object invokeModInfo(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getOwningFile" -> borrowedFileInfo == null ? fileInfo : borrowedFileInfo;
                case "getConfig" -> fileInfo;
                case "getModId", "getNamespace" -> metadata.id();
                case "getDisplayName" -> displayName();
                case "getDescription" -> description();
                case "getVersion" -> types.defaultArtifactVersion.getConstructor(String.class).newInstance(metadata.version());
                case "getDependencies", "getForgeFeatures" -> List.of();
                case "getModProperties" -> modProperties();
                case "getUpdateURL", "getModURL", "getLogoFile" -> Optional.empty();
                case "getLogoBlur" -> false;
                case "getConfigElement" -> Optional.empty();
                case "getConfigList" -> List.of();
                case "toString" -> displayName() + " " + metadata.version();
                case "hashCode" -> metadata.id().hashCode();
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object invokeFileInfo(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getMods" -> List.of(modInfo);
                case "requiredLanguageLoaders", "usesServices", "getConfigList" -> List.of();
                case "showAsResourcePack" -> false;
                case "getFileProperties" -> Map.of("intermed", true);
                case "getLicense" -> "unknown";
                case "moduleName" -> "intermed." + metadata.id().replace('-', '_');
                case "versionString" -> metadata.version();
                case "getFile" -> modFile;
                case "getConfig" -> proxy;
                case "getConfigElement" -> Optional.empty();
                case "toString" -> "InterMedModFileInfo[" + metadata.id() + "]";
                case "hashCode" -> metadata.id().hashCode();
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object invokeModFile(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getLoaders", "getModInfos" -> method.getName().equals("getModInfos") ? List.of(modInfo) : List.of();
                case "findResource" -> sourcePath();
                case "getSubstitutionMap" -> (Supplier<Map<String, Object>>) Map::of;
                case "getType" -> Enum.valueOf((Class<Enum>) types.modFileType.asSubclass(Enum.class), "MOD");
                case "getFilePath" -> sourcePath();
                case "getSecureJar" -> null;
                case "setSecurityStatus" -> null;
                case "getScanResult" -> types.modFileScanData.getConstructor().newInstance();
                case "getFileName" -> metadata.sourceJar() == null ? metadata.id() : metadata.sourceJar().getName();
                case "getProvider" -> provider;
                case "getModFileInfo" -> fileInfo;
                case "toString" -> "InterMedModFile[" + metadata.id() + "]";
                case "hashCode" -> metadata.id().hashCode();
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object invokeProvider(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "name" -> "intermed";
                case "scanFile", "initArguments" -> null;
                case "isValid" -> true;
                case "toString" -> "InterMed";
                case "hashCode" -> 31;
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Path sourcePath() {
            return metadata.sourceJar() == null ? Path.of(".") : metadata.sourceJar().toPath();
        }

        private String sourceLabel() {
            return metadata.sourceJar() == null ? "runtime" : metadata.sourceJar().getName();
        }

        private String displayName() {
            String name = metadata.name();
            String mark = RuntimeModIndex.isLoaded(metadata.id()) ? INTERMED_UI_MARK : INTERMED_DISCOVERED_UI_MARK;
            return name.endsWith(mark) ? name : name + mark;
        }

        private String description() {
            if (RuntimeModIndex.isLoaded(metadata.id())) {
                return "Loaded by InterMed from " + sourceLabel();
            }
            return RuntimeModIndex.loadFailure(metadata.id())
                .map(failure -> "Discovered by InterMed from " + sourceLabel()
                    + " (not active in game: " + failure + ")")
                .orElseGet(() -> "Discovered by InterMed from " + sourceLabel()
                    + " (not fully activated yet: dependency resolution or DAG assembly incomplete)");
        }

        private Map<String, Object> modProperties() {
            LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
            properties.put("intermed", true);
            properties.put("intermed:platform", metadata.platform().name());
            properties.put("intermed:source", sourceLabel());
            properties.put("intermed:state", RuntimeModIndex.isLoaded(metadata.id()) ? "loaded" : "discovered");
            RuntimeModIndex.loadFailure(metadata.id())
                .ifPresent(failure -> properties.put("intermed:failure", failure));
            return Map.copyOf(properties);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE || returnType == Short.TYPE || returnType == Byte.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == List.class) {
            return List.of();
        }
        if (returnType == Map.class) {
            return Map.of();
        }
        if (returnType == Set.class) {
            return Set.of();
        }
        return null;
    }

    private static boolean sameModIds(List<?> left, List<?> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!String.valueOf(readModId(left.get(i))).equals(String.valueOf(readModId(right.get(i))))) {
                return false;
            }
        }
        return true;
    }

    private static int countMirroredCandidates(List<?> mods) {
        if (mods == null) {
            return 0;
        }
        int count = 0;
        for (Object mod : mods) {
            if (mod == null) {
                continue;
            }
            try {
                Method method = mod.getClass().getMethod("getModProperties");
                Object properties = method.invoke(mod);
                if (properties instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("intermed"))) {
                    count++;
                }
            } catch (Exception ignored) {
                // Ignore vanilla/Forge mod entries that do not expose InterMed markers.
            }
        }
        return count;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void rebuildScreenState(Object screen) throws Exception {
        Object modList = readFieldIfPresent(screen, "modList");
        if (modList == null) {
            return;
        }

        invokeNoArgs(screen, "reloadMods");

        Object sortType = readFieldIfPresent(screen, "sortType");
        Object modsValue = readField(screen, "mods");
        if (modsValue instanceof List<?> mods && sortType instanceof java.util.Comparator<?> comparator) {
            ((List) mods).sort((java.util.Comparator) comparator);
            writeField(screen, "sorted", true);
        }

        invokeNoArgs(modList, "refreshList");
        reselectCurrentEntry(screen, modList);
        invokeNoArgs(screen, "updateCache");
    }

    private static void reselectCurrentEntry(Object screen, Object modList) {
        try {
            Object selected = readFieldIfPresent(screen, "selected");
            if (selected == null) {
                return;
            }
            Object selectedInfo = invokeNoArgs(selected, "getInfo");
            if (selectedInfo == null) {
                return;
            }

            Object entries = invokeNoArgs(modList, "m_6702_");
            if (!(entries instanceof Iterable<?> iterable)) {
                return;
            }
            for (Object candidate : iterable) {
                if (candidate == null) {
                    continue;
                }
                Object candidateInfo = invokeNoArgs(candidate, "getInfo");
                if (candidateInfo == selectedInfo) {
                    writeField(screen, "selected", candidate);
                    invokeMatchingMethod(modList, "m_6987_", candidate);
                    return;
                }
            }
            writeField(screen, "selected", null);
        } catch (Throwable ignored) {
            // Selection repair is best-effort; the list itself remains usable without it.
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object readFieldIfPresent(Object target, String name) {
        try {
            return readField(target, name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        java.lang.reflect.Method method = findMethod(target.getClass(), name, 0);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invokeMatchingMethod(Object target, String name, Object arg) throws Exception {
        Class<?> argType = arg == null ? Object.class : arg.getClass();
        java.lang.reflect.Method method = findMethod(target.getClass(), name, 1, argType);
        method.setAccessible(true);
        return method.invoke(target, arg);
    }

    private static java.lang.reflect.Method findMethod(Class<?> type,
                                                       String name,
                                                       int parameterCount) throws NoSuchMethodException {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static java.lang.reflect.Method findMethod(Class<?> type,
                                                       String name,
                                                       int parameterCount,
                                                       Class<?> argumentType) throws NoSuchMethodException {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (argIsCompatible(parameterType, argumentType)) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static boolean argIsCompatible(Class<?> parameterType, Class<?> argumentType) {
        if (argumentType == Object.class) {
            return !parameterType.isPrimitive();
        }
        return parameterType.isAssignableFrom(argumentType);
    }
}
