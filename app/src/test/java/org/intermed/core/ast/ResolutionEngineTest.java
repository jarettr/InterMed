package org.intermed.core.ast;

import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResolutionEngineTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("mixin.conflict.policy");
        RuntimeConfig.reload();
    }

    private ClassNode buildVoidClass(String internalName, String methodName, int... opcodes) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        for (int opcode : opcodes) {
            mv.visitInsn(opcode);
        }
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildIntClass(String internalName, String methodName, int... opcodes) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        mv.visitCode();
        for (int opcode : opcodes) {
            mv.visitInsn(opcode);
        }
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildIntClassWithImmediate(String internalName, String methodName, int opcode, int operand) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(opcode, operand);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildBranchingVoidClass(String internalName, String methodName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        var label = new org.objectweb.asm.Label();
        mv.visitJumpInsn(Opcodes.GOTO, label);
        mv.visitLabel(label);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    @Test
    void testNoConflictAddsMethodFromMixin() {
        ClassNode target = buildVoidClass("com/example/Target", "tick", Opcodes.RETURN);
        ClassNode mixin = buildVoidClass("com/example/Mixin", "onLoad", Opcodes.RETURN);

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));

        assertEquals(1, report.addedMethods());
        assertEquals(2, target.methods.size());
        assertNotNull(target.methods.stream().filter(m -> m.name.equals("onLoad")).findFirst().orElse(null));
    }

    @Test
    void testIdenticalIncomingMethodIsDeduplicated() {
        ClassNode target = buildVoidClass("com/example/TargetSame", "tick", Opcodes.RETURN);
        ClassNode mixin = buildVoidClass("com/example/MixinSame", "tick", Opcodes.RETURN);

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));

        assertEquals(0, report.modifiedMethods());
        assertEquals(1, report.deduplicatedMethods());
        assertEquals(1, target.methods.size());
        assertFalse(target.methods.stream().anyMatch(m -> m.name.contains("$intermed")));
    }

    @Test
    void testLinearVoidConflictGetsSemanticInlineMerge() {
        ClassNode target = buildVoidClass(
            "com/example/TargetMerge",
            "tick",
            Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN
        );
        ClassNode mixin = buildVoidClass(
            "com/example/MixinMerge",
            "tick",
            Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN
        );

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));
        MethodNode tick = target.methods.stream().filter(m -> m.name.equals("tick")).findFirst().orElseThrow();

        assertEquals(1, report.semanticMerges());
        assertEquals(1, target.methods.size());
        assertFalse((tick.access & Opcodes.ACC_SYNTHETIC) != 0);
        assertEquals(List.of(
            Opcodes.ICONST_1,
            Opcodes.POP,
            Opcodes.ICONST_2,
            Opcodes.POP,
            Opcodes.RETURN
        ), realOpcodes(tick));
    }

    @Test
    void testLinearSupersetReplacesMethodWithoutBridge() {
        ClassNode target = buildIntClass(
            "com/example/TargetSuperset",
            "value",
            Opcodes.ICONST_1, Opcodes.IRETURN
        );
        ClassNode mixin = buildIntClass(
            "com/example/MixinSuperset",
            "value",
            Opcodes.NOP, Opcodes.ICONST_1, Opcodes.IRETURN
        );

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));
        MethodNode value = target.methods.stream().filter(m -> m.name.equals("value")).findFirst().orElseThrow();

        assertEquals(1, report.semanticMerges());
        assertEquals(1, target.methods.size());
        assertEquals(List.of(Opcodes.NOP, Opcodes.ICONST_1, Opcodes.IRETURN), realOpcodes(value));
        assertFalse(target.methods.stream().anyMatch(m -> m.name.contains("$intermed")));
    }

    @Test
    void testConflictFallsBackToSyntheticBridgeWhenMergeIsUnsafe() {
        ClassNode target = buildBranchingVoidClass("com/example/TargetConflict", "tick");
        ClassNode mixin = buildVoidClass("com/example/MixinConflict", "tick", Opcodes.RETURN);

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));

        boolean hasBridge = target.methods.stream()
            .anyMatch(m -> m.name.equals("tick") && (m.access & Opcodes.ACC_SYNTHETIC) != 0);
        boolean hasOrig = target.methods.stream().anyMatch(m -> m.name.equals("tick$intermed_orig"));
        boolean hasMixin = target.methods.stream().anyMatch(m -> m.name.equals("tick$intermed_mixin$0"));

        assertEquals(1, report.bridgeMethods());
        assertTrue(hasBridge);
        assertTrue(hasOrig);
        assertTrue(hasMixin);
    }

    @Test
    void overwritePolicyPrefersHighestPriorityIncomingMethod() {
        System.setProperty("mixin.conflict.policy", "overwrite");
        RuntimeConfig.reload();

        ClassNode target = buildBranchingVoidClass("com/example/TargetOverwritePolicy", "tick");
        ClassNode lowPriorityMixin = buildVoidClass("com/example/MixinLow", "tick", Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN);
        ClassNode highPriorityMixin = buildVoidClass("com/example/MixinHigh", "tick", Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN);

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("low.mixin", 500, lowPriorityMixin),
            new ResolutionEngine.MixinContribution("high.mixin", 1500, highPriorityMixin)
        ));

        MethodNode tick = target.methods.stream().filter(m -> m.name.equals("tick")).findFirst().orElseThrow();
        assertEquals(1, report.directReplacements());
        assertEquals(List.of(Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN), realOpcodes(tick));
        assertFalse(target.methods.stream().anyMatch(m -> m.name.contains("$intermed")));
    }

    @Test
    void failPolicyThrowsForUnsafeConflicts() {
        System.setProperty("mixin.conflict.policy", "fail");
        RuntimeConfig.reload();

        ClassNode target = buildBranchingVoidClass("com/example/TargetFailPolicy", "tick");
        ClassNode mixin = buildVoidClass("com/example/MixinFail", "tick", Opcodes.RETURN);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> ResolutionEngine.resolveMixinConflicts(target, List.of(mixin))
        );

        assertTrue(error.getMessage().contains("Unresolvable mixin conflict"));
    }

    @Test
    void explicitOverwriteConflictFallsBackToBridgeInsteadOfSemanticMerge() {
        ClassNode target = buildVoidClass(
            "com/example/TargetOverwriteBridge",
            "tick",
            Opcodes.ICONST_0, Opcodes.POP, Opcodes.RETURN
        );
        ClassNode overwriteMixin = buildVoidClass(
            "com/example/MixinOverwriteBridge",
            "tick",
            Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN
        );
        ClassNode injectLikeMixin = buildVoidClass(
            "com/example/MixinInjectBridge",
            "tick",
            Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN
        );

        annotate(overwriteMixin, "tick", "()V", "Lorg/spongepowered/asm/mixin/Overwrite;");
        annotate(injectLikeMixin, "tick", "()V", "Lorg/spongepowered/asm/mixin/injection/Inject;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("overwrite.mixin", 1200, overwriteMixin),
            new ResolutionEngine.MixinContribution("inject.mixin", 1000, injectLikeMixin)
        ));

        assertEquals(0, report.semanticMerges());
        assertEquals(1, report.bridgeMethods());
        assertTrue(target.methods.stream()
            .anyMatch(m -> m.name.equals("tick") && (m.access & Opcodes.ACC_SYNTHETIC) != 0));
    }

    @Test
    void renderPipelineConflictUsesSemanticIsolationInsteadOfBridge() {
        ClassNode target = buildBranchingVoidClass("net/minecraft/client/renderer/GameRenderer", "render");
        ClassNode mixin = buildVoidClass(
            "com/example/RenderMixin",
            "render",
            Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN
        );

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("render.mixin", 1200, 10, mixin)
        ));

        MethodNode render = target.methods.stream()
            .filter(m -> m.name.equals("render"))
            .findFirst()
            .orElseThrow();

        assertEquals(0, report.bridgeMethods());
        assertEquals(1, report.directReplacements());
        assertEquals("SEMANTIC_ISOLATION_RENDER_PIPELINE", report.conflicts().getFirst().resolution());
        assertEquals(List.of(Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN), realOpcodes(render));
        assertTrue(target.methods.stream().anyMatch(m -> m.name.startsWith("render$intermed_orig")));
        assertFalse((render.access & Opcodes.ACC_SYNTHETIC) != 0);
    }

    @Test
    void worldStateConflictUsesHighestPriorityWinnerAndIsolatesOthers() {
        ClassNode target = buildBranchingVoidClass("net/minecraft/server/level/ServerLevel", "tick");
        ClassNode lowPriorityMixin = buildVoidClass(
            "com/example/WorldMixinLow",
            "tick",
            Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN
        );
        ClassNode highPriorityMixin = buildVoidClass(
            "com/example/WorldMixinHigh",
            "tick",
            Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN
        );

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("world.low", 900, 20, lowPriorityMixin),
            new ResolutionEngine.MixinContribution("world.high", 1500, 30, highPriorityMixin)
        ));

        MethodNode tick = target.methods.stream()
            .filter(m -> m.name.equals("tick"))
            .findFirst()
            .orElseThrow();

        assertEquals(0, report.bridgeMethods());
        assertEquals(1, report.directReplacements());
        assertEquals("SEMANTIC_ISOLATION_WORLD_STATE", report.conflicts().getFirst().resolution());
        assertEquals(List.of(Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN), realOpcodes(tick));
        assertTrue(target.methods.stream().anyMatch(m -> m.name.startsWith("tick$intermed_orig")));
        assertTrue(target.methods.stream().anyMatch(m -> m.name.startsWith("tick$intermed_isolated$")));
    }

    @Test
    void semanticallyEquivalentOverwriteIsDeduplicatedWithoutBridge() {
        ClassNode target = buildIntClass(
            "com/example/TargetEquivalentOverwrite",
            "value",
            Opcodes.ICONST_1, Opcodes.IRETURN
        );
        ClassNode overwriteMixin = buildIntClassWithImmediate(
            "com/example/MixinEquivalentOverwrite",
            "value",
            Opcodes.BIPUSH,
            1
        );

        annotate(overwriteMixin, "value", "()I", "Lorg/spongepowered/asm/mixin/Overwrite;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("equivalent.overwrite", 1000, overwriteMixin)
        ));

        assertEquals(0, report.modifiedMethods());
        assertEquals(1, report.deduplicatedMethods());
        assertEquals(0, report.bridgeMethods());
        assertEquals(1, target.methods.size());
        assertFalse(target.methods.stream().anyMatch(m -> m.name.contains("$intermed")));
    }

    @Test
    void mixinExtrasAnnotationsAreTreatedAsInjectLike() {
        ClassNode target = buildIntClass(
            "com/example/TargetExtras",
            "value",
            Opcodes.ICONST_1, Opcodes.IRETURN
        );
        ClassNode mixinOne = buildIntClassWithImmediate(
            "com/example/MixinExtrasOne",
            "value",
            Opcodes.BIPUSH,
            2
        );
        ClassNode mixinTwo = buildIntClassWithImmediate(
            "com/example/MixinExtrasTwo",
            "value",
            Opcodes.BIPUSH,
            3
        );

        annotate(mixinOne, "value", "()I", "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
        annotate(mixinTwo, "value", "()I", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("extras.one", 1000, mixinOne),
            new ResolutionEngine.MixinContribution("extras.two", 900, mixinTwo)
        ));

        assertEquals(0, report.modifiedMethods());
        assertEquals(0, report.bridgeMethods());
        assertEquals(2, report.addedMethods());
        assertTrue(target.methods.stream().anyMatch(m -> m.name.startsWith("value$im")));
    }

    @Test
    void renderPipelineInjectHandlersUsePriorityQueueAndPreserveApplicationOrder() {
        ClassNode target = buildVoidClass(
            "net/minecraft/client/gui/Gui",
            "render",
            Opcodes.RETURN
        );
        ClassNode earlyMixin = buildVoidClass(
            "com/example/RenderInjectEarly",
            "render",
            Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN
        );
        ClassNode lateMixin = buildVoidClass(
            "com/example/RenderInjectLate",
            "render",
            Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN
        );

        annotate(earlyMixin, "render", "()V", "Lorg/spongepowered/asm/mixin/injection/Inject;");
        annotate(lateMixin, "render", "()V", "Lorg/spongepowered/asm/mixin/injection/Inject;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflictsWithMetadata(target, List.of(
            new ResolutionEngine.MixinContribution("late.inject", 1000, 20, lateMixin),
            new ResolutionEngine.MixinContribution("early.inject", 1000, 10, earlyMixin)
        ));

        List<MethodNode> queued = target.methods.stream()
            .filter(m -> m.name.startsWith("render$intermed_queue$"))
            .sorted(java.util.Comparator.comparing(m -> m.name))
            .toList();

        assertEquals(0, report.bridgeMethods());
        assertEquals(2, report.addedMethods());
        assertEquals("SEMANTIC_PRIORITY_QUEUE_RENDER_PIPELINE", report.conflicts().getFirst().resolution());
        assertEquals(2, queued.size());
        assertEquals(List.of(Opcodes.ICONST_1, Opcodes.POP, Opcodes.RETURN), realOpcodes(queued.get(0)));
        assertEquals(List.of(Opcodes.ICONST_2, Opcodes.POP, Opcodes.RETURN), realOpcodes(queued.get(1)));
    }

    @Test
    void shadowMethodsAreIgnored() {
        ClassNode target = buildVoidClass("com/example/TargetShadow", "tick", Opcodes.RETURN);
        ClassNode shadowMixin = buildVoidClass("com/example/ShadowMixin", "shadowed", Opcodes.RETURN);

        annotate(shadowMixin, "shadowed", "()V", "Lorg/spongepowered/asm/mixin/Shadow;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(shadowMixin));

        assertEquals(0, report.addedMethods());
        assertEquals(1, target.methods.size());
        assertTrue(target.methods.stream().noneMatch(m -> m.name.equals("shadowed")));
    }

    @Test
    void accessorIsAddedWhenTargetMethodIsMissing() {
        ClassNode target = buildClassWithField("com/example/TargetAccessor", "value", "I");
        ClassNode accessorMixin = buildAbstractMethodClass(
            "com/example/AccessorMixin",
            "getValue",
            "()I"
        );

        annotate(accessorMixin, "getValue", "()I", "Lorg/spongepowered/asm/mixin/Accessor;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(accessorMixin));

        assertEquals(1, report.addedMethods());
        MethodNode generated = target.methods.stream()
            .filter(m -> m.name.equals("getValue"))
            .findFirst()
            .orElse(null);
        assertNotNull(generated);
        assertEquals(List.of(Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.IRETURN), realOpcodes(generated));
    }

    @Test
    void accessorConflictWithTargetMethodFailsFast() {
        ClassNode target = buildIntClass("com/example/TargetAccessorConflict", "getValue", Opcodes.ICONST_1, Opcodes.IRETURN);
        ClassNode accessorMixin = buildAbstractMethodClass(
            "com/example/AccessorConflictMixin",
            "getValue",
            "()I"
        );

        annotate(accessorMixin, "getValue", "()I", "Lorg/spongepowered/asm/mixin/Accessor;");

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> ResolutionEngine.resolveMixinConflicts(target, List.of(accessorMixin))
        );

        assertTrue(error.getMessage().contains("Accessor/Invoker conflict"));
    }

    @Test
    void invokerGeneratesCallToTargetMethod() {
        ClassNode target = buildClassWithMethod("com/example/TargetInvoker", "compute", "()I",
            Opcodes.ICONST_5, Opcodes.IRETURN);
        ClassNode invokerMixin = buildAbstractMethodClass(
            "com/example/InvokerMixin",
            "callCompute",
            "()I"
        );

        annotate(invokerMixin, "callCompute", "()I", "Lorg/spongepowered/asm/mixin/Invoker;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(invokerMixin));

        assertEquals(1, report.addedMethods());
        MethodNode generated = target.methods.stream()
            .filter(m -> m.name.equals("callCompute"))
            .findFirst()
            .orElse(null);
        assertNotNull(generated);
        assertTrue(realOpcodes(generated).contains(Opcodes.INVOKEVIRTUAL));
    }

    @Test
    void uniqueHelperIsRenamedAndCallSitesAreRewritten() {
        ClassNode target = buildClassWithMethod("com/example/TargetUnique", "helper", "()V",
            Opcodes.RETURN);
        ClassNode mixin = buildMixinWithHelper("com/example/UniqueMixin", "helper", "tick");

        annotate(mixin, "helper", "()V", "Lorg/spongepowered/asm/mixin/Unique;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));

        assertEquals(2, report.addedMethods());
        MethodNode renamedHelper = target.methods.stream()
            .filter(m -> m.name.startsWith("helper$im_u"))
            .findFirst()
            .orElse(null);
        assertNotNull(renamedHelper);

        MethodNode tick = target.methods.stream()
            .filter(m -> m.name.equals("tick"))
            .findFirst()
            .orElse(null);
        assertNotNull(tick);

        MethodInsnNode call = java.util.Arrays.stream(tick.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .findFirst()
            .orElse(null);
        assertNotNull(call);
        assertEquals("com/example/TargetUnique", call.owner);
        assertEquals(renamedHelper.name, call.name);
    }

    @Test
    void uniqueFieldRenameRewritesFieldAccesses() {
        ClassNode target = buildClassWithField("com/example/TargetFieldUnique", "value", "I");
        ClassNode mixin = buildMixinWithFieldAndGetter("com/example/UniqueFieldMixin", "value", "I", "readValue");

        annotateField(mixin, "value", "I", "Lorg/spongepowered/asm/mixin/Unique;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));

        assertEquals(1, report.addedMethods());
        FieldNode renamedField = target.fields.stream()
            .filter(field -> field.name.startsWith("value$im_u"))
            .findFirst()
            .orElse(null);
        assertNotNull(renamedField);

        MethodNode readValue = target.methods.stream()
            .filter(m -> m.name.equals("readValue"))
            .findFirst()
            .orElse(null);
        assertNotNull(readValue);

        FieldInsnNode access = java.util.Arrays.stream(readValue.instructions.toArray())
            .filter(FieldInsnNode.class::isInstance)
            .map(FieldInsnNode.class::cast)
            .findFirst()
            .orElse(null);
        assertNotNull(access);
        assertEquals("com/example/TargetFieldUnique", access.owner);
        assertEquals(renamedField.name, access.name);
        assertEquals("I", access.desc);
    }

    @Test
    void shadowFieldIsIgnored() {
        ClassNode target = buildClassWithField("com/example/TargetShadowField", "value", "I");
        ClassNode mixin = buildMixinWithFieldAndGetter("com/example/ShadowFieldMixin", "value", "I", "readValue");

        annotateField(mixin, "value", "I", "Lorg/spongepowered/asm/mixin/Shadow;");

        ResolutionEngine.ResolutionReport report = ResolutionEngine.resolveMixinConflicts(target, List.of(mixin));

        assertEquals(1, report.addedMethods());
        assertEquals(1, target.fields.size());
        assertTrue(target.fields.stream().anyMatch(field -> field.name.equals("value")));

        MethodNode readValue = target.methods.stream()
            .filter(m -> m.name.equals("readValue"))
            .findFirst()
            .orElse(null);
        assertNotNull(readValue);

        FieldInsnNode access = java.util.Arrays.stream(readValue.instructions.toArray())
            .filter(FieldInsnNode.class::isInstance)
            .map(FieldInsnNode.class::cast)
            .findFirst()
            .orElse(null);
        assertNotNull(access);
        assertEquals("com/example/TargetShadowField", access.owner);
        assertEquals("value", access.name);
    }

    @Test
    void fieldConflictFailsFast() {
        ClassNode target = buildClassWithField("com/example/TargetFieldConflict", "value", "I");
        ClassNode mixin = buildClassWithField("com/example/FieldConflictMixin", "value", "I");

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> ResolutionEngine.resolveMixinConflicts(target, List.of(mixin))
        );

        assertTrue(error.getMessage().contains("Field conflict"));
    }

    private List<Integer> realOpcodes(MethodNode method) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .map(AbstractInsnNode::getOpcode)
            .filter(opcode -> opcode >= 0)
            .toList();
    }

    private ClassNode buildAbstractMethodClass(String internalName, String methodName, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, methodName, desc, null, null).visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildClassWithField(String internalName, String fieldName, String fieldDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, fieldName, fieldDesc, null, null).visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildMixinWithFieldAndGetter(String internalName,
                                                   String fieldName,
                                                   String fieldDesc,
                                                   String getterName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, fieldName, fieldDesc, null, null).visitEnd();

        MethodVisitor getter = cw.visitMethod(Opcodes.ACC_PUBLIC, getterName, "()" + fieldDesc, null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, internalName, fieldName, fieldDesc);
        getter.visitInsn(returnOpcodeFor(fieldDesc));
        getter.visitMaxs(1, 1);
        getter.visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildClassWithMethod(String internalName, String methodName, String desc, int... opcodes) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, desc, null, null);
        mv.visitCode();
        for (int opcode : opcodes) {
            mv.visitInsn(opcode);
        }
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private ClassNode buildMixinWithHelper(String internalName, String helperName, String callerName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null);

        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PUBLIC, helperName, "()V", null, null);
        helper.visitCode();
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(0, 1);
        helper.visitEnd();

        MethodVisitor caller = cw.visitMethod(Opcodes.ACC_PUBLIC, callerName, "()V", null, null);
        caller.visitCode();
        caller.visitVarInsn(Opcodes.ALOAD, 0);
        caller.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, helperName, "()V", false);
        caller.visitInsn(Opcodes.RETURN);
        caller.visitMaxs(1, 1);
        caller.visitEnd();

        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        ClassNode node = new ClassNode();
        cr.accept(node, 0);
        return node;
    }

    private int returnOpcodeFor(String fieldDesc) {
        return switch (fieldDesc) {
            case "I", "Z", "B", "S", "C" -> Opcodes.IRETURN;
            case "J" -> Opcodes.LRETURN;
            case "F" -> Opcodes.FRETURN;
            case "D" -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }

    private void annotate(ClassNode node, String methodName, String desc, String annotationDesc) {
        MethodNode method = node.methods.stream()
            .filter(candidate -> candidate.name.equals(methodName) && candidate.desc.equals(desc))
            .findFirst()
            .orElseThrow();
        if (method.visibleAnnotations == null) {
            method.visibleAnnotations = new java.util.ArrayList<>();
        }
        method.visibleAnnotations.add(new AnnotationNode(annotationDesc));
    }

    private void annotateField(ClassNode node, String fieldName, String fieldDesc, String annotationDesc) {
        FieldNode field = node.fields.stream()
            .filter(candidate -> candidate.name.equals(fieldName) && candidate.desc.equals(fieldDesc))
            .findFirst()
            .orElseThrow();
        if (field.visibleAnnotations == null) {
            field.visibleAnnotations = new java.util.ArrayList<>();
        }
        field.visibleAnnotations.add(new AnnotationNode(annotationDesc));
    }
}
