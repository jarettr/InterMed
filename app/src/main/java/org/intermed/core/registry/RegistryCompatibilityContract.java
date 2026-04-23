package org.intermed.core.registry;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical registry compatibility contract shared by the bytecode transformer
 * and runtime tests.
 */
public final class RegistryCompatibilityContract {

    private static final int OPCODE_INVOKEVIRTUAL = 182;
    private static final int OPCODE_INVOKESTATIC = 184;
    private static final int OPCODE_INVOKEINTERFACE = 185;

    private static final Set<String> PAYLOAD_LOOKUP_OWNERS = Set.of(
        "net/minecraft/class_2378",
        "net/minecraft/core/Registry",
        "net/minecraft/core/DefaultedRegistry",
        "net/minecraft/core/WritableRegistry",
        "net/minecraft/core/MappedRegistry",
        "net/minecraftforge/registries/IForgeRegistry",
        "net/minecraftforge/registries/ForgeRegistry",
        "net/neoforged/neoforge/registries/IForgeRegistry",
        "net/neoforged/neoforge/registries/NeoForgeRegistry"
    );

    private static final Set<String> FACADE_OWNERS = Set.of(
        "net/minecraft/core/RegistryAccess",
        "net/minecraft/core/RegistryAccess$Frozen",
        "net/minecraft/core/RegistryAccess$ImmutableRegistryAccess",
        "net/minecraft/core/LayeredRegistryAccess",
        "net/minecraft/core/HolderLookup$RegistryLookup",
        "net/minecraft/core/HolderLookup$Provider",
        "net/minecraft/resources/BuiltInRegistries",
        "net/minecraft/core/registries/BuiltInRegistries"
    );

    private RegistryCompatibilityContract() {}

    public static Set<String> payloadLookupOwners() {
        return PAYLOAD_LOOKUP_OWNERS;
    }

    public static Set<String> facadeOwners() {
        return FACADE_OWNERS;
    }

    public static Set<String> payloadLookupBinaryOwners() {
        return PAYLOAD_LOOKUP_OWNERS.stream()
            .map(RegistryCompatibilityContract::toBinaryName)
            .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> facadeBinaryOwners() {
        return FACADE_OWNERS.stream()
            .map(RegistryCompatibilityContract::toBinaryName)
            .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isRegisterCall(int opcode,
                                         String owner,
                                         String method,
                                         String methodDescriptor) {
        if (!isRegisterOwner(owner)) {
            return false;
        }
        if (looksLikeRegistryRegisterSignature(opcode, owner, methodDescriptor)) {
            return true;
        }
        return switch (method) {
            case "method_10226", "method_10230", "method_10231", "method_39197", "register" -> true;
            default -> false;
        };
    }

    public static boolean isGetCall(int opcode,
                                    String owner,
                                    String method,
                                    String methodDescriptor) {
        if (opcode != OPCODE_INVOKEVIRTUAL
            && opcode != OPCODE_INVOKEINTERFACE
            && opcode != OPCODE_INVOKESTATIC) {
            return false;
        }
        if (!isPayloadLookupOwner(owner) && !isFacadePayloadLookup(owner, method, methodDescriptor)) {
            return false;
        }
        return switch (method) {
            case "method_17966",
                 "method_36376",
                 "method_10176",
                 "get",
                 "getValue",
                 "getOptional",
                 "getRawId",
                 "getId" -> true;
            case "registryOrThrow",
                 "registry",
                 "byName" -> isFacadePayloadLookup(owner, method, methodDescriptor);
            default -> isLikelyMappedAlias(method)
                && looksLikeMappedPayloadGetSignature(owner, methodDescriptor);
        };
    }

    public static boolean isRegisterOwner(String owner) {
        return isPayloadLookupOwner(owner);
    }

    private static boolean looksLikeRegistryRegisterSignature(int opcode,
                                                              String owner,
                                                              String methodDescriptor) {
        if (opcode != OPCODE_INVOKESTATIC || methodDescriptor == null) {
            return false;
        }

        MethodDescriptor descriptor = parseMethodDescriptor(methodDescriptor);
        if (descriptor == null) {
            return false;
        }
        String[] args = descriptor.argumentDescriptors();
        if (args.length < 3 || args.length > 4) {
            return false;
        }
        if (!("L" + owner + ";").equals(args[0])) {
            return false;
        }
        String returnType = descriptor.returnDescriptor();
        String valueType = args[args.length - 1];
        if (!returnType.equals(valueType)) {
            return false;
        }
        String keyType = args[1];
        if (args.length == 3) {
            return isRegistryKeyLike(keyType);
        }

        return "I".equals(keyType) && isRegistryKeyLike(args[2]);
    }

    private static boolean isRegistryKeyLike(String descriptor) {
        return "Ljava/lang/String;".equals(descriptor)
            || "Lnet/minecraft/resources/ResourceLocation;".equals(descriptor)
            || "Lnet/minecraft/resources/ResourceKey;".equals(descriptor)
            || "Lnet/minecraft/class_2960;".equals(descriptor)
            || "Lnet/minecraft/class_5321;".equals(descriptor);
    }

    public static boolean isPayloadLookupOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return false;
        }
        return PAYLOAD_LOOKUP_OWNERS.contains(owner)
            || owner.startsWith("net/minecraftforge/registries/")
            || owner.startsWith("net/neoforged/neoforge/registries/");
    }

    public static boolean isFacadeOwner(String owner) {
        return owner != null && FACADE_OWNERS.contains(owner);
    }

    public static boolean isFacadePayloadLookup(String owner,
                                                String method,
                                                String methodDescriptor) {
        if (!isFacadeOwner(owner)) {
            return false;
        }
        if (methodDescriptor == null || !returnsPayloadLike(methodDescriptor)) {
            return false;
        }
        return switch (method) {
            case "registryOrThrow", "registry", "get", "byName", "getValue", "getId" -> true;
            default -> false;
        };
    }

    private static boolean looksLikeMappedPayloadGetSignature(String owner, String methodDescriptor) {
        if (!isPayloadLookupOwner(owner)) {
            return false;
        }
        MethodDescriptor descriptor = parseMethodDescriptor(methodDescriptor);
        if (descriptor == null) {
            return false;
        }
        String[] args = descriptor.argumentDescriptors();
        if (args.length != 1) {
            return false;
        }

        String arg = args[0];
        String returnType = descriptor.returnDescriptor();
        if ("Ljava/lang/Object;".equals(returnType)) {
            return isRegistryKeyLike(arg) || "Ljava/lang/Object;".equals(arg);
        }
        if ("Ljava/util/Optional;".equals(returnType)) {
            return isRegistryKeyLike(arg);
        }
        if ("I".equals(returnType)) {
            return "Ljava/lang/Object;".equals(arg);
        }
        return false;
    }

    private static boolean isLikelyMappedAlias(String method) {
        if (method == null || method.isBlank()) {
            return false;
        }
        if (method.length() == 1) {
            return true;
        }
        return method.startsWith("m_") && method.endsWith("_");
    }

    public static boolean returnsPayloadLike(String methodDescriptor) {
        MethodDescriptor descriptor = parseMethodDescriptor(methodDescriptor);
        if (descriptor == null) {
            return false;
        }
        String returnType = descriptor.returnDescriptor();
        if ("I".equals(returnType)) {
            return true;
        }
        if (returnType.isEmpty()) {
            return false;
        }
        if (returnType.charAt(0) == '[') {
            return true;
        }
        if (returnType.charAt(0) != 'L' || !returnType.endsWith(";")) {
            return false;
        }
        String internalName = returnType.substring(1, returnType.length() - 1);
        return !internalName.equals("java/util/Optional")
            && !internalName.startsWith("net/minecraft/core/Registry")
            && !internalName.startsWith("net/minecraft/core/Holder")
            && !internalName.startsWith("net/minecraft/core/HolderLookup")
            && !internalName.startsWith("net/minecraft/resources/ResourceKey")
            && !internalName.startsWith("com/mojang/serialization/Codec");
    }

    private static String toBinaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    private static MethodDescriptor parseMethodDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank() || descriptor.charAt(0) != '(') {
            return null;
        }
        int cursor = 1;
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        while (cursor < descriptor.length() && descriptor.charAt(cursor) != ')') {
            int next = skipType(descriptor, cursor);
            if (next <= cursor) {
                return null;
            }
            args.add(descriptor.substring(cursor, next));
            cursor = next;
        }
        if (cursor >= descriptor.length() || descriptor.charAt(cursor) != ')') {
            return null;
        }
        int returnStart = cursor + 1;
        int returnEnd = skipType(descriptor, returnStart);
        if (returnEnd != descriptor.length()) {
            return null;
        }
        return new MethodDescriptor(args.toArray(String[]::new), descriptor.substring(returnStart, returnEnd));
    }

    private static int skipType(String descriptor, int start) {
        if (start < 0 || start >= descriptor.length()) {
            return -1;
        }
        char ch = descriptor.charAt(start);
        return switch (ch) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> start + 1;
            case 'L' -> {
                int end = descriptor.indexOf(';', start);
                yield end < 0 ? -1 : end + 1;
            }
            case '[' -> {
                int nested = start;
                while (nested < descriptor.length() && descriptor.charAt(nested) == '[') {
                    nested++;
                }
                yield skipType(descriptor, nested);
            }
            default -> -1;
        };
    }

    private record MethodDescriptor(String[] argumentDescriptors, String returnDescriptor) {}
}
