package org.intermed.core.ast;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstMetadataReclaimerTest {

    @Test
    void reclaimsTargetAndMixinTreesAfterAnalysis() {
        ClassNode target = createClassNode("demo/Target");
        ClassNode mixin = createClassNode("demo/Mixin");

        AstMetadataReclaimer.ReclaimStats stats = AstMetadataReclaimer.reclaim(
            target,
            List.of(new ResolutionEngine.MixinContribution("demo.Mixin", 1000, 0, mixin))
        );

        assertEquals(2, stats.classCount());
        assertEquals(2, stats.methodCount());
        assertEquals(2, stats.fieldCount());
        assertEquals(4, stats.instructionCount());
        // After reclaim, methods and fields are nulled — not just cleared — so that any
        // post-reclaim access on a shared ClassNode fails with NPE rather than silently
        // returning empty results.
        assertNull(target.methods);
        assertNull(target.fields);
        assertNull(mixin.methods);
        assertNull(mixin.fields);
        assertNull(target.signature);
        assertNull(mixin.sourceFile);
    }

    private static ClassNode createClassNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        node.interfaces = new ArrayList<>(List.of("java/io/Serializable"));
        node.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ldemo/Visible;")));

        FieldNode field = new FieldNode(Opcodes.ACC_PRIVATE, "value", "I", "I", Integer.valueOf(7));
        field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ldemo/Field;")));
        node.fields = new ArrayList<>(List.of(field));

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", "sig", null);
        method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Ldemo/Method;")));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods = new ArrayList<>(List.of(method));
        return node;
    }
}
