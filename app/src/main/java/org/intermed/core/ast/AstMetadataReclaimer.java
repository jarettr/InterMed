package org.intermed.core.ast;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;

/**
 * Best-effort scrubbing of transient ASM trees after AOT resolution.
 *
 * <p>ASM {@link ClassNode} graphs are allocation-heavy and can pin large
 * instruction lists until the next major collection. Once a transformed class
 * has been emitted and cached, InterMed no longer needs these trees, so we
 * aggressively clear them to reduce retained heap and make classloader teardown
 * cheaper after initialization.
 */
public final class AstMetadataReclaimer {

    private AstMetadataReclaimer() {}

    public static ReclaimStats reclaim(ClassNode targetNode,
                                       Collection<ResolutionEngine.MixinContribution> contributions) {
        ReclaimStats total = reclaim(targetNode);
        if (contributions == null || contributions.isEmpty()) {
            return total;
        }
        for (ResolutionEngine.MixinContribution contribution : contributions) {
            if (contribution == null) {
                continue;
            }
            total = total.merge(reclaim(contribution.classNode()));
        }
        return total;
    }

    public static ReclaimStats reclaim(ClassNode classNode) {
        if (classNode == null) {
            return ReclaimStats.EMPTY;
        }
        // Guard against double-reclaim: both lists are already null → nothing to reclaim.
        // This can happen if a ClassNode is accidentally passed through reclaim() twice.
        if (classNode.methods == null && classNode.fields == null) {
            return ReclaimStats.EMPTY;
        }

        int methodCount = classNode.methods == null ? 0 : classNode.methods.size();
        int fieldCount = classNode.fields == null ? 0 : classNode.fields.size();
        int instructionCount = 0;

        if (classNode.methods != null) {
            for (MethodNode method : classNode.methods) {
                if (method == null) {
                    continue;
                }
                if (method.instructions != null) {
                    instructionCount += method.instructions.size();
                    method.instructions.clear();
                }
                if (method.tryCatchBlocks != null) {
                    method.tryCatchBlocks.clear();
                }
                if (method.localVariables != null) {
                    method.localVariables.clear();
                }
                if (method.visibleAnnotations != null) {
                    method.visibleAnnotations.clear();
                }
                if (method.invisibleAnnotations != null) {
                    method.invisibleAnnotations.clear();
                }
                if (method.visibleTypeAnnotations != null) {
                    method.visibleTypeAnnotations.clear();
                }
                if (method.invisibleTypeAnnotations != null) {
                    method.invisibleTypeAnnotations.clear();
                }
                if (method.attrs != null) {
                    method.attrs.clear();
                }
                if (method.parameters != null) {
                    method.parameters.clear();
                }
                if (method.exceptions != null) {
                    method.exceptions.clear();
                }
                method.annotationDefault = null;
                method.signature = null;
            }
            classNode.methods.clear();
            classNode.methods = null;
        }

        if (classNode.fields != null) {
            for (FieldNode field : classNode.fields) {
                if (field == null) {
                    continue;
                }
                if (field.visibleAnnotations != null) {
                    field.visibleAnnotations.clear();
                }
                if (field.invisibleAnnotations != null) {
                    field.invisibleAnnotations.clear();
                }
                if (field.visibleTypeAnnotations != null) {
                    field.visibleTypeAnnotations.clear();
                }
                if (field.invisibleTypeAnnotations != null) {
                    field.invisibleTypeAnnotations.clear();
                }
                if (field.attrs != null) {
                    field.attrs.clear();
                }
                field.signature = null;
                field.value = null;
            }
            classNode.fields.clear();
            classNode.fields = null;
        }

        if (classNode.interfaces != null) {
            classNode.interfaces.clear();
        }
        if (classNode.innerClasses != null) {
            classNode.innerClasses.clear();
        }
        if (classNode.recordComponents != null) {
            classNode.recordComponents.clear();
        }
        if (classNode.visibleAnnotations != null) {
            classNode.visibleAnnotations.clear();
        }
        if (classNode.invisibleAnnotations != null) {
            classNode.invisibleAnnotations.clear();
        }
        if (classNode.visibleTypeAnnotations != null) {
            classNode.visibleTypeAnnotations.clear();
        }
        if (classNode.invisibleTypeAnnotations != null) {
            classNode.invisibleTypeAnnotations.clear();
        }
        if (classNode.attrs != null) {
            classNode.attrs.clear();
        }
        if (classNode.permittedSubclasses != null) {
            classNode.permittedSubclasses.clear();
        }
        if (classNode.nestMembers != null) {
            classNode.nestMembers.clear();
        }
        classNode.signature = null;
        classNode.sourceFile = null;
        classNode.sourceDebug = null;
        classNode.outerClass = null;
        classNode.outerMethod = null;
        classNode.outerMethodDesc = null;

        return new ReclaimStats(1, methodCount, fieldCount, instructionCount);
    }

    public record ReclaimStats(int classCount,
                               int methodCount,
                               int fieldCount,
                               int instructionCount) {

        static final ReclaimStats EMPTY = new ReclaimStats(0, 0, 0, 0);

        public ReclaimStats merge(ReclaimStats other) {
            if (other == null) {
                return this;
            }
            return new ReclaimStats(
                classCount + other.classCount,
                methodCount + other.methodCount,
                fieldCount + other.fieldCount,
                instructionCount + other.instructionCount
            );
        }

        public boolean hasReclaimedContent() {
            return classCount > 0 || methodCount > 0 || fieldCount > 0 || instructionCount > 0;
        }
    }
}
