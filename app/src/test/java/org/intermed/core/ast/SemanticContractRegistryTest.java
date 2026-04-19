package org.intermed.core.ast;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticContractRegistryTest {

    @Test
    void descriptorAndLocalStateSignalsMarkRenderPipelineAsStrict() {
        MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC, "drawFrame",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;)V", null, null);
        target.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        target.instructions.add(new InsnNode(Opcodes.POP));
        target.instructions.add(new InsnNode(Opcodes.RETURN));

        SemanticContractRegistry.SemanticContract contract = SemanticContractRegistry.resolve(
            "com/example/render/OverlayRenderer",
            "drawFrame",
            "(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            target,
            List.of()
        );

        assertEquals(SemanticContractRegistry.SemanticZone.RENDER_PIPELINE, contract.zone());
        assertTrue(contract.strictOrder());
        assertTrue(contract.bridgeUnsafe());
        assertTrue(contract.signals().contains("descriptor-scope"));
        assertTrue(contract.signals().contains("local-state"));
    }

    @Test
    void worldMutationSignalsMarkWorldStateCritical() {
        MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        target.instructions.add(new InsnNode(Opcodes.ICONST_1));
        target.instructions.add(new FieldInsnNode(
            Opcodes.PUTFIELD,
            "net/minecraft/server/level/ServerLevel",
            "updatingBlockEntities",
            "Z"
        ));
        target.instructions.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "net/minecraft/server/level/ServerLevel",
            "setBlock",
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            false
        ));
        target.instructions.add(new InsnNode(Opcodes.POP));
        target.instructions.add(new InsnNode(Opcodes.RETURN));

        SemanticContractRegistry.SemanticContract contract = SemanticContractRegistry.resolve(
            "net/minecraft/server/level/ServerLevel",
            "tick",
            "()V",
            target,
            List.of()
        );

        assertEquals(SemanticContractRegistry.SemanticZone.WORLD_STATE, contract.zone());
        assertTrue(contract.bridgeUnsafe());
        assertTrue(contract.signals().contains("owner-scope"));
        assertTrue(contract.signals().contains("field-write"));
        assertTrue(contract.signals().contains("world-api"));
    }

    @Test
    void controlFlowOnlyMethodOutsideCriticalScopesStaysNonCritical() {
        MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC, "compute", "()V", null, null);
        LabelNode exit = new LabelNode();
        target.instructions.add(new JumpInsnNode(Opcodes.GOTO, exit));
        target.instructions.add(exit);
        target.instructions.add(new InsnNode(Opcodes.RETURN));

        SemanticContractRegistry.SemanticContract contract = SemanticContractRegistry.resolve(
            "com/example/util/MathHelper",
            "compute",
            "()V",
            target,
            List.of()
        );

        assertFalse(contract.isCritical());
        assertEquals(SemanticContractRegistry.SemanticZone.NONE, contract.zone());
    }

    @Test
    void networkPacketHandlerIsMarkedNetworkIo() {
        MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC, "handlePacket",
            "(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;Lnet/minecraft/server/network/ServerPlayPacketListener;)V",
            null, null);
        target.instructions.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "net/minecraft/network/connection/Connection",
            "sendPacket",
            "(Lnet/minecraft/network/protocol/Packet;)V",
            false
        ));
        target.instructions.add(new InsnNode(Opcodes.RETURN));

        SemanticContractRegistry.SemanticContract contract = SemanticContractRegistry.resolve(
            "net/minecraft/network/protocol/game/ServerGamePacketListenerImpl",
            "handlePacket",
            "(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;Lnet/minecraft/server/network/ServerPlayPacketListener;)V",
            target,
            List.of()
        );

        assertEquals(SemanticContractRegistry.SemanticZone.NETWORK_IO, contract.zone());
        assertTrue(contract.strictOrder());
        assertTrue(contract.bridgeUnsafe());
        Set<String> signals = new java.util.HashSet<>(contract.signals());
        assertTrue(signals.contains("owner-scope") || signals.contains("descriptor-scope"),
            "expected owner-scope or descriptor-scope signal, got: " + signals);
    }

    @Test
    void registryRegisterCallIsMarkedRegistryMutation() {
        MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC, "register",
            "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)Ljava/lang/Object;",
            null, null);
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        target.instructions.add(new InsnNode(Opcodes.ICONST_0));
        target.instructions.add(new FieldInsnNode(
            Opcodes.PUTFIELD,
            "net/minecraft/core/registries/MappedRegistry",
            "frozen",
            "Z"
        ));
        target.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        target.instructions.add(new InsnNode(Opcodes.ARETURN));

        SemanticContractRegistry.SemanticContract contract = SemanticContractRegistry.resolve(
            "net/minecraft/core/registries/MappedRegistry",
            "register",
            "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)Ljava/lang/Object;",
            target,
            List.of()
        );

        assertEquals(SemanticContractRegistry.SemanticZone.REGISTRY_MUTATION, contract.zone());
        assertTrue(contract.strictOrder());
        assertTrue(contract.bridgeUnsafe());
        assertTrue(contract.signals().contains("owner-scope"));
        assertTrue(contract.signals().contains("field-write"));
    }
}
