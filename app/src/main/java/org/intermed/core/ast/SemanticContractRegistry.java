package org.intermed.core.ast;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Semantic contracts for JVM hot spots where "execute everyone" resolution is
 * unsafe even when bytecode-level conflicts look bridgeable.
 *
 * <p>The registry classifies critical methods through a combination of:
 * owner/descriptor scope, hot-path method naming, and bytecode-side signals
 * such as local-state mutation, control-flow state machines, field writes, and
 * render/world API calls. The result is still intentionally conservative.
 */
public final class SemanticContractRegistry {

    private static final SemanticContract NONE =
        new SemanticContract(SemanticZone.NONE, "non-critical", false, false, List.of());

    private static final List<String> RENDER_OWNER_MARKERS = List.of(
        "/client/renderer/",
        "/client/gui/",
        "/client/model/",
        "/client/particle/",
        "/blaze3d/",
        "/renderer/",
        "/gui/"
    );

    private static final List<String> WORLD_OWNER_MARKERS = List.of(
        "/server/level/",
        "/world/level/",
        "/world/entity/",
        "/world/level/block/",
        "/world/level/chunk/",
        "/level/chunk/",
        "/server/network/"
    );

    private static final List<String> NETWORK_OWNER_MARKERS = List.of(
        "/network/protocol/",
        "/network/chat/",
        "/network/syncher/",
        "/server/network/",
        "/client/multiplayer/",
        "/network/connection/",
        "/network/packet/"
    );

    private static final List<String> REGISTRY_OWNER_MARKERS = List.of(
        "/core/registries/",
        "/core/registry/",
        "/registry/",
        "/registries/"
    );

    private static final List<String> RENDER_TYPE_MARKERS = List.of(
        "posestack",
        "matrix4f",
        "rendersystem",
        "bufferbuilder",
        "vertexconsumer",
        "multibuffersource",
        "guigraphics"
    );

    private static final List<String> WORLD_TYPE_MARKERS = List.of(
        "serverlevel",
        "levelchunk",
        "blockpos",
        "blockstate",
        "entity",
        "player",
        "chunkaccess",
        "levelaccessor"
    );

    private static final List<String> NETWORK_TYPE_MARKERS = List.of(
        "friendlybytebuf",
        "packetbuffer",
        "packetlistener",
        "connection",
        "servercommonpacketlistener",
        "clientcommonpacketlistener",
        "serverplaypacketlistener",
        "clientplaypacketlistener"
    );

    private static final List<String> REGISTRY_TYPE_MARKERS = List.of(
        "registry",
        "registryaccess",
        "writableregistry",
        "mappedregistry",
        "resourcekey",
        "resourcelocation"
    );

    private static final List<String> RENDER_METHOD_MARKERS = List.of(
        "render",
        "draw",
        "blit",
        "setshader",
        "setshadercolor",
        "setshadertexture",
        "setuprender",
        "renderoverlay",
        "renderhud"
    );

    private static final List<String> WORLD_METHOD_MARKERS = List.of(
        "tick",
        "setblock",
        "setblockandupdate",
        "markandnotifyblock",
        "neighborchanged",
        "onplace",
        "onremove",
        "addfreshentity",
        "addentity",
        "removeentity",
        "spawn",
        "destroy",
        "update"
    );

    private static final List<String> NETWORK_METHOD_MARKERS = List.of(
        "handlepacket",
        "sendevent",
        "sendpacket",
        "dispatch",
        "processpacket",
        "onpacket",
        "handle",
        "receive",
        "decode",
        "encode"
    );

    private static final List<String> REGISTRY_METHOD_MARKERS = List.of(
        "register",
        "registerorinvalidate",
        "freeze",
        "registernew",
        "bindtags",
        "createtag",
        "unfreeze",
        "validatetag",
        "registryholder"
    );

    private SemanticContractRegistry() {}

    public static SemanticContract resolve(String targetClassInternalName,
                                           String methodName,
                                           String methodDesc) {
        return resolve(targetClassInternalName, methodName, methodDesc, null, List.of());
    }

    public static SemanticContract resolve(String targetClassInternalName,
                                           String methodName,
                                           String methodDesc,
                                           MethodNode targetMethod,
                                           List<MethodNode> incomingMethods) {
        String owner = normalize(targetClassInternalName);
        String method = normalize(methodName);
        String desc = normalize(methodDesc);
        LinkedHashSet<String> signals = new LinkedHashSet<>();

        if (isRenderPipeline(owner, method, desc, targetMethod, incomingMethods, signals)) {
            return buildContract(SemanticZone.RENDER_PIPELINE,
                targetClassInternalName, methodName, methodDesc, signals);
        }
        // REGISTRY_MUTATION and NETWORK_IO are checked before WORLD_STATE because
        // writesMutableState() in worldMode is a broad catch-all that would otherwise
        // absorb any field-writing registry or network method.
        if (isRegistryMutation(owner, method, desc, targetMethod, incomingMethods, signals)) {
            return buildContract(SemanticZone.REGISTRY_MUTATION,
                targetClassInternalName, methodName, methodDesc, signals);
        }
        if (isNetworkIo(owner, method, desc, targetMethod, incomingMethods, signals)) {
            return buildContract(SemanticZone.NETWORK_IO,
                targetClassInternalName, methodName, methodDesc, signals);
        }
        if (isWorldStateMutation(owner, method, desc, targetMethod, incomingMethods, signals)) {
            return buildContract(SemanticZone.WORLD_STATE,
                targetClassInternalName, methodName, methodDesc, signals);
        }
        return NONE;
    }

    private static SemanticContract buildContract(SemanticZone zone,
                                                  String targetClassInternalName,
                                                  String methodName,
                                                  String methodDesc,
                                                  Set<String> signals) {
        LinkedHashSet<String> effectiveSignals = new LinkedHashSet<>(signals);
        boolean strictOrder = zone == SemanticZone.RENDER_PIPELINE
            || zone == SemanticZone.NETWORK_IO
            || zone == SemanticZone.REGISTRY_MUTATION
            || effectiveSignals.contains("local-state")
            || effectiveSignals.contains("control-flow")
            || effectiveSignals.contains("field-write");
        boolean bridgeUnsafe = zone != SemanticZone.NONE;
        String rationale = zone.name().toLowerCase(Locale.ROOT)
            + " contract matched "
            + targetClassInternalName + "#" + methodName + methodDesc
            + " signals=" + effectiveSignals;
        return new SemanticContract(zone, rationale, strictOrder, bridgeUnsafe, List.copyOf(effectiveSignals));
    }

    private static boolean isRenderPipeline(String owner,
                                            String method,
                                            String desc,
                                            MethodNode targetMethod,
                                            List<MethodNode> incomingMethods,
                                            Set<String> signals) {
        boolean matched = false;
        if (containsAny(owner, RENDER_OWNER_MARKERS)) {
            signals.add("owner-scope");
            matched = true;
        }
        if (containsAny(desc, RENDER_TYPE_MARKERS)) {
            signals.add("descriptor-scope");
            matched = true;
        }
        if (containsAny(method, RENDER_METHOD_MARKERS)
            && (owner.contains("/client/") || owner.contains("/renderer/") || owner.contains("/gui/"))) {
            signals.add("render-method");
            matched = true;
        }
        if (scanSignals(targetMethod, incomingMethods, true, false, signals)) {
            matched = true;
        }
        return matched;
    }

    private static boolean isWorldStateMutation(String owner,
                                                String method,
                                                String desc,
                                                MethodNode targetMethod,
                                                List<MethodNode> incomingMethods,
                                                Set<String> signals) {
        boolean matched = false;
        if (containsAny(owner, WORLD_OWNER_MARKERS)) {
            signals.add("owner-scope");
            matched = true;
        }
        if (containsAny(desc, WORLD_TYPE_MARKERS)) {
            signals.add("descriptor-scope");
            matched = true;
        }
        if (containsAny(method, WORLD_METHOD_MARKERS) && containsAny(owner, WORLD_OWNER_MARKERS)) {
            signals.add("world-method");
            matched = true;
        }
        if (scanSignals(targetMethod, incomingMethods, false, true, signals)) {
            matched = true;
        }
        return matched;
    }

    private static boolean isNetworkIo(String owner,
                                       String method,
                                       String desc,
                                       MethodNode targetMethod,
                                       List<MethodNode> incomingMethods,
                                       Set<String> signals) {
        boolean matched = false;
        if (containsAny(owner, NETWORK_OWNER_MARKERS)) {
            signals.add("owner-scope");
            matched = true;
        }
        if (containsAny(desc, NETWORK_TYPE_MARKERS)) {
            signals.add("descriptor-scope");
            matched = true;
        }
        if (containsAny(method, NETWORK_METHOD_MARKERS) && containsAny(owner, NETWORK_OWNER_MARKERS)) {
            signals.add("network-method");
            matched = true;
        }
        if (targetMethod != null || (incomingMethods != null && !incomingMethods.isEmpty())) {
            boolean networkApi = scanNetworkApiSignals(targetMethod, incomingMethods, signals);
            if (networkApi) {
                matched = true;
            }
        }
        return matched;
    }

    private static boolean isRegistryMutation(String owner,
                                              String method,
                                              String desc,
                                              MethodNode targetMethod,
                                              List<MethodNode> incomingMethods,
                                              Set<String> signals) {
        boolean matched = false;
        if (containsAny(owner, REGISTRY_OWNER_MARKERS)) {
            signals.add("owner-scope");
            matched = true;
        }
        if (containsAny(desc, REGISTRY_TYPE_MARKERS)) {
            signals.add("descriptor-scope");
            matched = true;
        }
        if (containsAny(method, REGISTRY_METHOD_MARKERS) && containsAny(owner, REGISTRY_OWNER_MARKERS)) {
            signals.add("registry-method");
            matched = true;
        }
        if (writesMutableState(targetMethod)) {
            signals.add("field-write");
            matched = matched || containsAny(owner, REGISTRY_OWNER_MARKERS);
        }
        if (incomingMethods != null) {
            for (MethodNode incoming : incomingMethods) {
                if (incoming != null && writesMutableState(incoming)) {
                    signals.add("field-write");
                    matched = matched || containsAny(owner, REGISTRY_OWNER_MARKERS);
                }
            }
        }
        return matched;
    }

    private static boolean scanNetworkApiSignals(MethodNode targetMethod,
                                                 List<MethodNode> incomingMethods,
                                                 Set<String> signals) {
        boolean matched = false;
        if (targetMethod != null && inspectNetworkMethod(targetMethod, signals)) {
            matched = true;
        }
        if (incomingMethods != null) {
            for (MethodNode incoming : incomingMethods) {
                if (incoming != null && inspectNetworkMethod(incoming, signals)) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean inspectNetworkMethod(MethodNode method, Set<String> signals) {
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode invoke) {
                String invokeOwner = normalize(invoke.owner);
                String invokeName = normalize(invoke.name);
                String invokeDesc = normalize(invoke.desc);
                if (containsAny(invokeOwner, NETWORK_OWNER_MARKERS)
                    || containsAny(invokeDesc, NETWORK_TYPE_MARKERS)
                    || containsAny(invokeName, NETWORK_METHOD_MARKERS)) {
                    signals.add("network-api");
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean scanSignals(MethodNode targetMethod,
                                       List<MethodNode> incomingMethods,
                                       boolean renderMode,
                                       boolean worldMode,
                                       Set<String> signals) {
        boolean matched = false;
        if (targetMethod != null && inspectMethod(targetMethod, renderMode, worldMode, signals)) {
            matched = true;
        }
        if (incomingMethods != null) {
            for (MethodNode incoming : incomingMethods) {
                if (incoming != null && inspectMethod(incoming, renderMode, worldMode, signals)) {
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean inspectMethod(MethodNode method,
                                         boolean renderMode,
                                         boolean worldMode,
                                         Set<String> signals) {
        boolean matched = false;
        if (touchesLocalState(method)) {
            signals.add("local-state");
        }
        if (containsStateMachineFlow(method)) {
            signals.add("control-flow");
        }
        if (writesMutableState(method)) {
            signals.add("field-write");
            matched = worldMode;
        }

        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode invoke) {
                String invokeOwner = normalize(invoke.owner);
                String invokeName = normalize(invoke.name);
                String invokeDesc = normalize(invoke.desc);
                if (renderMode && (containsAny(invokeOwner, RENDER_OWNER_MARKERS)
                    || containsAny(invokeOwner, RENDER_TYPE_MARKERS)
                    || containsAny(invokeName, RENDER_METHOD_MARKERS)
                    || containsAny(invokeDesc, RENDER_TYPE_MARKERS))) {
                    signals.add("render-api");
                    matched = true;
                }
                if (worldMode && (containsAny(invokeOwner, WORLD_OWNER_MARKERS)
                    || containsAny(invokeOwner, WORLD_TYPE_MARKERS)
                    || containsAny(invokeName, WORLD_METHOD_MARKERS)
                    || containsAny(invokeDesc, WORLD_TYPE_MARKERS))) {
                    signals.add("world-api");
                    matched = true;
                }
            } else if (instruction instanceof FieldInsnNode field) {
                String owner = normalize(field.owner);
                if (renderMode && (containsAny(owner, RENDER_OWNER_MARKERS) || containsAny(owner, RENDER_TYPE_MARKERS))) {
                    signals.add("render-field");
                    matched = true;
                }
                if (worldMode && (containsAny(owner, WORLD_OWNER_MARKERS) || containsAny(owner, WORLD_TYPE_MARKERS))) {
                    signals.add("world-field");
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean touchesLocalState(MethodNode method) {
        if (method == null) {
            return false;
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode var) {
                int opcode = var.getOpcode();
                if (opcode == Opcodes.ISTORE
                    || opcode == Opcodes.LSTORE
                    || opcode == Opcodes.FSTORE
                    || opcode == Opcodes.DSTORE
                    || opcode == Opcodes.ASTORE) {
                    return true;
                }
            }
            if (instruction instanceof IincInsnNode) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsStateMachineFlow(MethodNode method) {
        if (method == null) {
            return false;
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode
                || instruction instanceof TableSwitchInsnNode
                || instruction instanceof LookupSwitchInsnNode) {
                return true;
            }
        }
        return false;
    }

    private static boolean writesMutableState(MethodNode method) {
        if (method == null) {
            return false;
        }
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, List<String> needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public enum SemanticZone {
        NONE,
        RENDER_PIPELINE,
        WORLD_STATE,
        NETWORK_IO,
        REGISTRY_MUTATION
    }

    public record SemanticContract(SemanticZone zone,
                                   String rationale,
                                   boolean strictOrder,
                                   boolean bridgeUnsafe,
                                   List<String> signals) {
        public SemanticContract {
            if (zone == null) {
                zone = SemanticZone.NONE;
            }
            if (rationale == null || rationale.isBlank()) {
                rationale = "unspecified";
            }
            signals = signals == null ? List.of() : List.copyOf(signals);
        }

        public boolean isCritical() {
            return zone != SemanticZone.NONE;
        }
    }
}
