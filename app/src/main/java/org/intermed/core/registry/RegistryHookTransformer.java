package org.intermed.core.registry;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASM bytecode transformer for registry virtualisation (ТЗ 3.2.2, Requirement 3).
 *
 * <h3>What this transforms</h3>
 * For every class that passes through the InterMed ClassLoader pipeline, this
 * transformer scans all method bodies and replaces two categories of
 * instructions with {@code INVOKEDYNAMIC} calls linked to
 * {@link RegistryLinker}:
 *
 * <ol>
 *   <li><b>Register calls</b> — any {@code INVOKESTATIC} or {@code INVOKEVIRTUAL}
 *       whose owner+name matches a known registry-register pattern.  The
 *       bootstrap {@link RegistryLinker#bootstrapRegister} mirrors the object
 *       into {@link VirtualRegistry} and returns it unchanged.</li>
 *   <li><b>Get / lookup calls</b> — any {@code INVOKEVIRTUAL} or
 *       {@code INVOKEINTERFACE} whose owner+name matches a known registry-get
 *       pattern.  The bootstrap {@link RegistryLinker#bootstrapGet} returns
 *       the virtualised object and self-patches after
 *       {@link VirtualRegistryService#freeze()}.</li>
 * </ol>
 *
 * <h3>Supported registry patterns</h3>
 * <table border="1">
 *   <tr><th>Ecosystem</th><th>Class (owner)</th><th>Method</th></tr>
 *   <tr><td>Fabric (intermediary)</td><td>{@code net/minecraft/class_2378}</td>
 *       <td>{@code method_10226} (register)</td></tr>
 *   <tr><td>Fabric (intermediary)</td><td>{@code net/minecraft/class_2378}</td>
 *       <td>{@code method_17966}, {@code method_36376}, {@code method_10176}
 *           (get, getOrEmpty, getRawId)</td></tr>
 *   <tr><td>Mojang / Forge</td><td>{@code net/minecraft/core/Registry},
 *       {@code net/minecraft/core/WritableRegistry},
 *       {@code net/minecraft/core/MappedRegistry}</td>
 *       <td>{@code register}, {@code get}, {@code getValue}, {@code getOptional},
 *           {@code getRawId}</td></tr>
 *   <tr><td>Forge</td><td>{@code net/minecraftforge/registries/IForgeRegistry}</td>
 *       <td>{@code register}, {@code getValue}, {@code getOptional}</td></tr>
 *   <tr><td>NeoForge</td><td>{@code net/neoforged/neoforge/registries/IForgeRegistry}</td>
 *       <td>{@code register}, {@code getValue}, {@code getOptional}</td></tr>
 * </table>
 *
 * <p>Registry-access facades introduced in Minecraft 1.20.5+
 * ({@code RegistryAccess}, {@code HolderLookup}, {@code BuiltInRegistries})
 * are rewritten only when the bytecode signature looks like a payload lookup.
 * Calls that still return registry views / holder indirections remain untouched.
 */
public class RegistryHookTransformer implements BytecodeTransformer {
    private static final AtomicInteger REWRITTEN_REGISTER_SITES = new AtomicInteger();
    private static final AtomicInteger REWRITTEN_GET_SITES = new AtomicInteger();

    // ── Bootstrap method descriptor (common to both BSMs) ─────────────────────
    private static final String BSM_DESCRIPTOR =
        "(Ljava/lang/invoke/MethodHandles$Lookup;"
        + "Ljava/lang/String;"
        + "Ljava/lang/invoke/MethodType;"
        + ")Ljava/lang/invoke/CallSite;";

    private static final String LINKER = "org/intermed/core/registry/RegistryLinker";

    private static final Handle BSM_REGISTER = new Handle(
        Opcodes.H_INVOKESTATIC, LINKER, "bootstrapRegister", BSM_DESCRIPTOR, false);

    private static final Handle BSM_GET = new Handle(
        Opcodes.H_INVOKESTATIC, LINKER, "bootstrapGet", BSM_DESCRIPTOR, false);

    // =========================================================================
    // BytecodeTransformer
    // =========================================================================

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        try {
            ClassReader  reader = new ClassReader(originalBytes);
            ClassWriter  writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name,
                                                 String descriptor,
                                                 String signature,
                                                 String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(
                        access, name, descriptor, signature, exceptions);
                    return new RegistryMethodVisitor(mv);
                }
            };

            reader.accept(visitor, 0);
            return writer.toByteArray();
        } catch (Exception e) {
            // Never break the class — return original bytes on any failure
            return originalBytes;
        }
    }

    // =========================================================================
    // Inner visitor — replaces registry call instructions
    // =========================================================================

    private static final class RegistryMethodVisitor extends MethodVisitor {

        RegistryMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String methodName,
                                    String methodDescriptor, boolean isInterface) {

            if (RegistryCompatibilityContract.isRegisterCall(opcode, owner, methodName, methodDescriptor)) {
                REWRITTEN_REGISTER_SITES.incrementAndGet();
                emitInvokeDynamic("register", methodDescriptor,
                    opcode, owner, BSM_REGISTER);

            } else if (RegistryCompatibilityContract.isGetCall(opcode, owner, methodName, methodDescriptor)) {
                REWRITTEN_GET_SITES.incrementAndGet();
                emitInvokeDynamic("registryGet", methodDescriptor,
                    opcode, owner, BSM_GET);

            } else {
                super.visitMethodInsn(opcode, owner, methodName,
                    methodDescriptor, isInterface);
            }
        }

        /**
         * Replaces the current method call with an INVOKEDYNAMIC whose
         * descriptor preserves the exact stack shape.  For INVOKEVIRTUAL /
         * INVOKEINTERFACE the receiver is implicit in the original instruction
         * but must become an explicit first parameter in the INVOKEDYNAMIC
         * descriptor — we prepend the owner type.
         */
        private void emitInvokeDynamic(String dynName, String originalDesc,
                                       int originalOpcode, String owner,
                                       Handle bsm) {
            String idyDesc = originalDesc; // INVOKESTATIC: descriptor already complete

            if (originalOpcode == Opcodes.INVOKEVIRTUAL
                    || originalOpcode == Opcodes.INVOKEINTERFACE) {
                // Prepend receiver (owner type) to the parameter list
                Type   ownerType  = Type.getObjectType(owner);
                Type   origMethod = Type.getMethodType(originalDesc);
                Type[] origParams = origMethod.getArgumentTypes();
                Type[] newParams  = new Type[origParams.length + 1];
                newParams[0]      = ownerType;
                System.arraycopy(origParams, 0, newParams, 1, origParams.length);
                idyDesc = Type.getMethodDescriptor(origMethod.getReturnType(), newParams);
            }

            super.visitInvokeDynamicInsn(dynName, idyDesc, bsm);
        }
    }

    // =========================================================================
    // Pattern matching helpers
    // =========================================================================

    static int rewrittenRegisterSiteCount() {
        return REWRITTEN_REGISTER_SITES.get();
    }

    static int rewrittenGetSiteCount() {
        return REWRITTEN_GET_SITES.get();
    }

    static void resetForTests() {
        REWRITTEN_REGISTER_SITES.set(0);
        REWRITTEN_GET_SITES.set(0);
    }

}
