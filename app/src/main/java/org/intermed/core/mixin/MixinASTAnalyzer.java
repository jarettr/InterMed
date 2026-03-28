package org.intermed.core.mixin;

import org.intermed.core.lifecycle.LifecycleManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Optional;

/**
 * Resolution Engine (ТЗ 3.2.3, Требование 6).
 * Строит AST-дерево класса для выявления и разрешения конфликтов миксинов.
 */
public class MixinASTAnalyzer {

    public static byte[] analyzeAndResolve(String className, byte[] originalBytes, List<MixinInfo> mixinInfos) {
        try {
            ClassReader cr = new ClassReader(originalBytes);
            ClassNode targetNode = new ClassNode();
            cr.accept(targetNode, 0);

            boolean hasModifications = false;

            for (MixinInfo info : mixinInfos) {
                // Load the mixin class bytes
                byte[] mixinBytes = LifecycleManager.getClassBytesFromDAG(info.getMixinClass());
                if (mixinBytes == null) continue;

                ClassReader mixinCr = new ClassReader(mixinBytes);
                ClassNode mixinNode = new ClassNode();
                mixinCr.accept(mixinNode, 0);

                for (MethodNode mixinMethod : mixinNode.methods) {
                    if (mixinMethod.visibleAnnotations != null) {
                        for (AnnotationNode annotation : mixinMethod.visibleAnnotations) {
                            if (annotation.desc.contains("org/spongepowered/asm/mixin/Overwrite")) {
                                System.out.println("\033[1;36m[AST-Engine] Applying @Overwrite from " + info.getMixinClass() + " to " + className + "." + mixinMethod.name + "\033[0m");

                                // Find the target method in the target class
                                Optional<MethodNode> targetMethodOpt = targetNode.methods.stream()
                                    .filter(m -> m.name.equals(mixinMethod.name) && m.desc.equals(mixinMethod.desc))
                                    .findFirst();

                                if (targetMethodOpt.isPresent()) {
                                    MethodNode targetMethod = targetMethodOpt.get();
                                    // Simple overwrite: replace the instructions
                                    targetMethod.instructions = mixinMethod.instructions;
                                    hasModifications = true;
                                } else {
                                     System.err.println("[AST-Engine] @Overwrite failed: Method " + mixinMethod.name + " not found in target " + className);
                                }
                            }
                        }
                    }
                }
            }

            if (hasModifications) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                targetNode.accept(cw);
                return cw.toByteArray();
            }

            return originalBytes;

        } catch (Exception e) {
            System.err.println("[AST-Engine] Failed to analyze AST for " + className + ": " + e.getMessage());
            e.printStackTrace();
            return originalBytes; // Fallback
        }
    }
}