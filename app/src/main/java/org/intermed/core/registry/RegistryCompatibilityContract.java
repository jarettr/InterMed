package org.intermed.core.registry;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Canonical registry compatibility contract shared by the bytecode transformer
 * and runtime tests.
 */
public final class RegistryCompatibilityContract {

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

    public static boolean isRegisterCall(int opcode, String owner, String method) {
        if (!isRegisterOwner(owner)) {
            return false;
        }
        return switch (method) {
            case "method_10226", "register" -> true;
            default -> false;
        };
    }

    public static boolean isGetCall(int opcode,
                                    String owner,
                                    String method,
                                    String methodDescriptor) {
        if (opcode != Opcodes.INVOKEVIRTUAL
            && opcode != Opcodes.INVOKEINTERFACE
            && opcode != Opcodes.INVOKESTATIC) {
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
            default -> false;
        };
    }

    public static boolean isRegisterOwner(String owner) {
        return isPayloadLookupOwner(owner);
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

    public static boolean returnsPayloadLike(String methodDescriptor) {
        Type returnType = Type.getReturnType(methodDescriptor);
        if (returnType.getSort() == Type.INT) {
            return true;
        }
        if (returnType.getSort() != Type.OBJECT && returnType.getSort() != Type.ARRAY) {
            return false;
        }
        if (returnType.getSort() == Type.ARRAY) {
            return true;
        }

        String internalName = returnType.getInternalName();
        if (internalName == null) {
            return false;
        }
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
}
