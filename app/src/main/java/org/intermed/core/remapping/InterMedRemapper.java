package org.intermed.core.remapping;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.intermed.core.classloading.DagAwareClassWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central runtime remapping service used by ClassLoader pipelines and reflection shims.
 */
public final class InterMedRemapper {
    private static final MappingDictionary EMPTY_DICTIONARY = new MappingDictionary();
    private static volatile MappingDictionary dictionary = EMPTY_DICTIONARY;

    // No bytecode transform cache: transformed bytes are used once by defineClass()
    // then never needed again (subsequent loads hit the classloader's classCache).
    // Keeping them caused unbounded heap growth proportional to mod class count.
    private static final Map<String, String> STRING_CACHE = new ConcurrentHashMap<>();

    private InterMedRemapper() {}

    public static void installDictionary(MappingDictionary mappingDictionary) {
        dictionary = mappingDictionary == null ? EMPTY_DICTIONARY : mappingDictionary;
        clearCaches();
    }

    public static void clearCaches() {
        STRING_CACHE.clear();
    }

    public static String remapBinaryClassName(String binaryName) {
        if (binaryName == null || binaryName.isBlank()) {
            return binaryName;
        }
        String internalName = MappingDictionary.normalizeInternalName(binaryName);
        String mapped = dictionary.map(internalName);
        if (mapped == null) {
            return binaryName;
        }
        return binaryName.indexOf('.') >= 0 ? mapped.replace('/', '.') : mapped;
    }

    public static String translateRuntimeString(String original) {
        if (original == null || original.isBlank()) {
            return original;
        }
        return STRING_CACHE.computeIfAbsent(original, InterMedRemapper::translateRuntimeStringUncached);
    }

    public static byte[] transformClassBytes(String className, byte[] rawBytes) {
        return transformClassBytes(className, rawBytes, true);
    }

    public static byte[] transformClassBytes(String className, byte[] rawBytes, boolean instrumentReflectionStrings) {
        Objects.requireNonNull(rawBytes, "rawBytes");
        if (!dictionary.hasMappings()) {
            return rawBytes;
        }
        try {
            ClassReader reader = new ClassReader(rawBytes);
            ClassWriter writer = DagAwareClassWriter.create(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            Remapper asmRemapper = new RuntimeAsmRemapper(dictionary);
            ClassRemapper classRemapper = new ClassRemapper(writer, asmRemapper);
            if (instrumentReflectionStrings) {
                reader.accept(new ReflectionTransformer(classRemapper), ClassReader.EXPAND_FRAMES);
            } else {
                reader.accept(classRemapper, ClassReader.EXPAND_FRAMES);
            }
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[Remapper] Failed to transform " + className + ": " + t.getMessage());
            return rawBytes;
        }
    }

    private static String translateRuntimeStringUncached(String original) {
        String candidate = original;
        boolean dotted = candidate.indexOf('.') >= 0 && candidate.indexOf('/') < 0;
        String internalCandidate = dotted ? candidate.replace('.', '/') : candidate;

        String directClass = dictionary.map(internalCandidate);
        if (directClass != null) {
            return dotted ? directClass.replace('/', '.') : directClass;
        }

        String descriptorTranslation = tryTranslateDescriptor(candidate);
        if (descriptorTranslation != null) {
            return descriptorTranslation;
        }

        String replaced = candidate;
        for (Map.Entry<String, String> entry : dictionary.classesView().entrySet()) {
            String sourceInternal = entry.getKey();
            String targetInternal = entry.getValue();
            String source = dotted ? sourceInternal.replace('/', '.') : sourceInternal;
            String target = dotted ? targetInternal.replace('/', '.') : targetInternal;
            if (replaced.contains(source)) {
                replaced = replaced.replace(source, target);
            }
        }

        replaced = replaced.replaceAll("(?<![A-Za-z0-9_$])method_(\\d+)(?![A-Za-z0-9_$])", "m_$1_");
        replaced = replaced.replaceAll("(?<![A-Za-z0-9_$])field_(\\d+)(?![A-Za-z0-9_$])", "f_$1_");
        return replaced;
    }

    private static String tryTranslateDescriptor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.startsWith("(")) {
                Type methodType = Type.getMethodType(value);
                String[] mappedArgs = new String[methodType.getArgumentTypes().length];
                Type[] arguments = methodType.getArgumentTypes();
                for (int i = 0; i < arguments.length; i++) {
                    mappedArgs[i] = remapTypeDescriptor(arguments[i]);
                }
                String mappedReturn = remapTypeDescriptor(methodType.getReturnType());
                return Type.getMethodDescriptor(Type.getType(mappedReturn),
                    java.util.Arrays.stream(mappedArgs).map(Type::getType).toArray(Type[]::new));
            }
            if (value.startsWith("L") || value.startsWith("[")) {
                return remapTypeDescriptor(Type.getType(value));
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static String remapTypeDescriptor(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> {
                String mapped = dictionary.map(type.getInternalName());
                yield mapped != null ? Type.getObjectType(mapped).getDescriptor() : type.getDescriptor();
            }
            case Type.ARRAY -> {
                String elementDescriptor = remapTypeDescriptor(type.getElementType());
                yield "[".repeat(type.getDimensions()) + elementDescriptor;
            }
            default -> type.getDescriptor();
        };
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(value));
        }
    }

    private static final class RuntimeAsmRemapper extends Remapper {
        private final MappingDictionary dictionary;

        private RuntimeAsmRemapper(MappingDictionary dictionary) {
            this.dictionary = dictionary;
        }

        @Override
        public String map(String internalName) {
            return dictionary.mapClassName(internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return dictionary.mapMethodName(owner, name, descriptor);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return dictionary.mapFieldName(owner, name, descriptor);
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String stringValue) {
                return translateRuntimeString(stringValue);
            }
            return super.mapValue(value);
        }
    }
}
