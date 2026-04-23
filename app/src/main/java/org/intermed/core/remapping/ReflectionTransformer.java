package org.intermed.core.remapping;

import org.intermed.core.monitor.RiskyModRegistry;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites reflection-oriented string creation sites so they consult the runtime remapper.
 *
 * <p>Supported patterns:
 * <ul>
 *   <li>direct {@code ldc "net.minecraft..."} constants,</li>
 *   <li>{@code invokedynamic} string concatenation via {@code StringConcatFactory},</li>
 *   <li>{@code StringBuilder.append(...).toString()} chains — analysed with a
 *       proper def-use dataflow pass using {@link SourceInterpreter}, so the
 *       analysis is not bounded by a fixed instruction window.</li>
 * </ul>
 *
 * <p>When the transformer observes a dynamic concat site that smells like reflection
 * name construction but cannot confidently classify it, it emits a warning so the mod
 * can be treated as potentially unstable at runtime.
 */
public final class ReflectionTransformer extends ClassNode {
    private static final String REMAPPER_OWNER = "org/intermed/core/remapping/InterMedRemapper";
    private static final String REMAPPER_NAME = "translateRuntimeString";
    private static final String REMAPPER_DESC = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String SYMBOLIC_FACADE_OWNER = "org/intermed/core/remapping/SymbolicReflectionFacade";
    private static final String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

    private final ClassVisitor downstream;

    public ReflectionTransformer(ClassVisitor downstream) {
        super(Opcodes.ASM9);
        this.downstream = downstream;
    }

    @Override
    public void visitEnd() {
        for (MethodNode method : methods) {
            instrumentMethod(method);
        }
        accept(downstream);
    }

    private void instrumentMethod(MethodNode method) {
        boolean reflectionSinkPresent = methodContainsReflectionSink(method);
        boolean warnedAmbiguousConcat = false;
        List<AbstractInsnNode> instructions = new ArrayList<>(List.of(method.instructions.toArray()));
        for (AbstractInsnNode insn : instructions) {
            if (insn instanceof LdcInsnNode ldcInsn
                && ldcInsn.cst instanceof String stringValue
                && shouldInstrument(stringValue)) {
                injectTranslatorAfter(method, insn);
                continue;
            }

            if (reflectionSinkPresent
                && insn instanceof InvokeDynamicInsnNode indyInsn
                && isStringConcatFactory(indyInsn)) {
                ConcatDecision decision = analyzeIndyConcat(indyInsn);
                if (decision.instrument()) {
                    injectTranslatorAfter(method, insn);
                } else if (decision.warn() && !warnedAmbiguousConcat) {
                    warnedAmbiguousConcat = true;
                    RiskyModRegistry.markCurrentClassRisky("ambiguous invokedynamic concat in " + name + "." + method.name + method.desc);
                    System.err.println("[ReflectionRemap] Ambiguous dynamic concat in "
                        + name + "." + method.name + method.desc);
                }
                continue;
            }

            if (insn instanceof MethodInsnNode methodInsn) {
                if (rewriteReflectionSink(methodInsn)) {
                    continue;
                }
                int stringArgIndex = reflectionStringArgIndex(methodInsn);
                if (stringArgIndex >= 0 && injectTranslatorForArgument(method, methodInsn, stringArgIndex)) {
                    continue;
                }
            }

            if (reflectionSinkPresent
                && insn instanceof MethodInsnNode methodInsn
                && isStringBuilderToString(methodInsn)) {
                ConcatDecision decision = mergeDecisions(
                    analyzeStringBuilderChainDataflow(method, methodInsn),
                    analyzeStringBuilderChainLinear(methodInsn)
                );
                if (decision.instrument()) {
                    injectTranslatorAfter(method, insn);
                } else if (decision.warn() && !warnedAmbiguousConcat) {
                    warnedAmbiguousConcat = true;
                    RiskyModRegistry.markCurrentClassRisky("ambiguous StringBuilder concat in " + name + "." + method.name + method.desc);
                    System.err.println("[ReflectionRemap] Ambiguous StringBuilder concat in "
                        + name + "." + method.name + method.desc);
                }
                continue;
            }

            // String.format / String.formatted — intercept when a preceding literal
            // format string carries MC patterns like "net.minecraft.%s" or "class_%s".
            // The format call itself produces a new string that will contain those
            // patterns, so we wrap its return value with the runtime translator.
            if (reflectionSinkPresent
                && insn instanceof MethodInsnNode methodInsn
                && isStringFormat(methodInsn)) {
                if (formatCallHasSuspiciousFormatString(method, methodInsn)) {
                    injectTranslatorAfter(method, insn);
                } else if (!warnedAmbiguousConcat && formatCallHasStringArgs(methodInsn)) {
                    // Format string is not a suspicious literal (e.g. it comes from a variable),
                    // but the call site passes string arguments — we cannot statically rule out
                    // that the result encodes a remappable name.  Warn so the mod is flagged.
                    warnedAmbiguousConcat = true;
                    RiskyModRegistry.markCurrentClassRisky(
                        "unanalyzable String.format in " + name + "." + method.name + method.desc);
                    System.err.println("[ReflectionRemap] Unanalyzable String.format in "
                        + name + "." + method.name + method.desc
                        + " — format string not a compile-time constant; mod may be unstable");
                }
            }
        }
    }

    private static boolean methodContainsReflectionSink(MethodNode method) {
        if (method == null) {
            return false;
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode methodInsn
                && reflectionStringArgIndex(methodInsn) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void injectTranslatorAfter(MethodNode method, AbstractInsnNode insn) {
        method.instructions.insert(insn,
            new MethodInsnNode(Opcodes.INVOKESTATIC, REMAPPER_OWNER, REMAPPER_NAME, REMAPPER_DESC, false));
    }

    private static boolean injectTranslatorForArgument(MethodNode method,
                                                       MethodInsnNode call,
                                                       int stringArgIndex) {
        if (method == null || call == null) {
            return false;
        }
        Type[] args = Type.getArgumentTypes(call.desc);
        if (stringArgIndex < 0 || stringArgIndex >= args.length) {
            return false;
        }
        if (!isStringType(args[stringArgIndex])) {
            return false;
        }

        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        List<Type> stackTypes = new ArrayList<>();
        if (!isStatic) {
            stackTypes.add(Type.getObjectType(call.owner));
        }
        Collections.addAll(stackTypes, args);

        int[] locals = new int[stackTypes.size()];
        int nextLocal = method.maxLocals;
        for (int i = 0; i < stackTypes.size(); i++) {
            locals[i] = nextLocal;
            nextLocal += stackTypes.get(i).getSize();
        }

        InsnList patch = new InsnList();
        for (int i = stackTypes.size() - 1; i >= 0; i--) {
            Type type = stackTypes.get(i);
            patch.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), locals[i]));
        }

        int stringStackIndex = stringArgIndex + (isStatic ? 0 : 1);
        for (int i = 0; i < stackTypes.size(); i++) {
            Type type = stackTypes.get(i);
            int localIndex = locals[i];
            patch.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), localIndex));
            if (i == stringStackIndex) {
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REMAPPER_OWNER, REMAPPER_NAME, REMAPPER_DESC, false));
            }
        }

        method.instructions.insertBefore(call, patch);
        method.maxLocals = Math.max(method.maxLocals, nextLocal);
        return true;
    }

    private static int reflectionStringArgIndex(MethodInsnNode methodInsn) {
        if (methodInsn == null) {
            return -1;
        }
        String owner = methodInsn.owner;
        String name = methodInsn.name;
        if ("java/lang/Class".equals(owner)) {
            if ("forName".equals(name)
                || "getField".equals(name)
                || "getDeclaredField".equals(name)
                || "getMethod".equals(name)
                || "getDeclaredMethod".equals(name)) {
                return 0;
            }
        }
        if ("java/lang/ClassLoader".equals(owner)) {
            if ("loadClass".equals(name) || "findClass".equals(name)) {
                return 0;
            }
        }
        if ("java/lang/invoke/MethodHandles$Lookup".equals(owner)) {
            if (name.startsWith("find")) {
                return 1;
            }
        }
        return -1;
    }

    private static boolean rewriteReflectionSink(MethodInsnNode methodInsn) {
        if (methodInsn == null) {
            return false;
        }
        if ("java/lang/Class".equals(methodInsn.owner)) {
            if ("forName".equals(methodInsn.name)
                && ("(Ljava/lang/String;)Ljava/lang/Class;".equals(methodInsn.desc)
                    || "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;".equals(methodInsn.desc))) {
                methodInsn.owner = SYMBOLIC_FACADE_OWNER;
                methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                methodInsn.itf = false;
                return true;
            }
            if (("getMethod".equals(methodInsn.name) || "getDeclaredMethod".equals(methodInsn.name))
                && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(methodInsn.desc)) {
                methodInsn.owner = SYMBOLIC_FACADE_OWNER;
                methodInsn.desc = prependReceiverDescriptor("Ljava/lang/Class;", methodInsn.desc);
                methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                methodInsn.itf = false;
                return true;
            }
            if (("getField".equals(methodInsn.name) || "getDeclaredField".equals(methodInsn.name))
                && "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(methodInsn.desc)) {
                methodInsn.owner = SYMBOLIC_FACADE_OWNER;
                methodInsn.desc = prependReceiverDescriptor("Ljava/lang/Class;", methodInsn.desc);
                methodInsn.setOpcode(Opcodes.INVOKESTATIC);
                methodInsn.itf = false;
                return true;
            }
        }
        if ("java/lang/ClassLoader".equals(methodInsn.owner)
            && ("loadClass".equals(methodInsn.name) || "findClass".equals(methodInsn.name))
            && "(Ljava/lang/String;)Ljava/lang/Class;".equals(methodInsn.desc)) {
            methodInsn.owner = SYMBOLIC_FACADE_OWNER;
            methodInsn.desc = prependReceiverDescriptor("Ljava/lang/ClassLoader;", methodInsn.desc);
            methodInsn.setOpcode(Opcodes.INVOKESTATIC);
            methodInsn.itf = false;
            return true;
        }
        if ("java/lang/invoke/MethodHandles$Lookup".equals(methodInsn.owner)
            && isLookupBridgeCandidate(methodInsn.name)) {
            methodInsn.owner = SYMBOLIC_FACADE_OWNER;
            methodInsn.desc = prependReceiverDescriptor("Ljava/lang/invoke/MethodHandles$Lookup;", methodInsn.desc);
            methodInsn.setOpcode(Opcodes.INVOKESTATIC);
            methodInsn.itf = false;
            return true;
        }
        return false;
    }

    private static boolean isLookupBridgeCandidate(String methodName) {
        return "findVirtual".equals(methodName)
            || "findStatic".equals(methodName)
            || "findSpecial".equals(methodName)
            || "findGetter".equals(methodName)
            || "findSetter".equals(methodName)
            || "findStaticGetter".equals(methodName)
            || "findStaticSetter".equals(methodName);
    }

    private static String prependReceiverDescriptor(String receiverDescriptor, String originalDescriptor) {
        return "(" + receiverDescriptor + originalDescriptor.substring(1);
    }

    private static boolean isStringType(Type type) {
        return type.getSort() == Type.OBJECT && "java/lang/String".equals(type.getInternalName());
    }

    private static ConcatDecision analyzeIndyConcat(InvokeDynamicInsnNode indyInsn) {
        boolean suspicious = false;
        boolean hasRecipe = false;

        for (Object bootstrapArg : indyInsn.bsmArgs) {
            if (bootstrapArg instanceof String stringValue) {
                hasRecipe = true;
                if (shouldInstrument(stringValue)) {
                    suspicious = true;
                }
            }
        }

        if (suspicious) {
            return ConcatDecision.INSTRUMENT;
        }

        Type methodType = Type.getMethodType(indyInsn.desc);
        for (Type argumentType : methodType.getArgumentTypes()) {
            if (argumentType.getSort() == Type.OBJECT
                && "java/lang/String".equals(argumentType.getInternalName())) {
                return hasRecipe ? ConcatDecision.WARN : ConcatDecision.IGNORE;
            }
        }

        return ConcatDecision.IGNORE;
    }

    /**
     * Determines whether a {@code StringBuilder.toString()} call at {@code toStringInsn}
     * is assembling a string that contains Minecraft/intermediary names.
     *
     * <p>Uses a proper def-use dataflow analysis via {@link SourceInterpreter}:
     * <ol>
     *   <li>Run {@code Analyzer<SourceValue>} over {@code method} to compute per-instruction
     *       stack/local frames.</li>
     *   <li>For every {@code append(String)} call in the method, use the frame at that
     *       instruction to identify the string argument's producing instruction(s) and
     *       the receiver builder's "root" source (following ALOAD through local-variable
     *       state to reach the original NEW instruction).</li>
     *   <li>Accumulate a set of builder roots that had suspicious constants appended,
     *       and a set that had dynamic (non-constant) string args.</li>
     *   <li>At the target {@code toString()} call, resolve its receiver's roots and
     *       check membership in the above sets.</li>
     * </ol>
     *
     * <p>This replaces the old bounded 24-instruction backward scan; the analysis is
     * not limited by distance and correctly handles StringBuilders stored in locals.
     */
    private ConcatDecision analyzeStringBuilderChainDataflow(MethodNode method,
                                                             MethodInsnNode toStringInsn) {
        try {
            Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
            @SuppressWarnings("unchecked")
            Frame<SourceValue>[] frames = analyzer.analyze(this.name, method);
            AbstractInsnNode[] insns = method.instructions.toArray();

            // Step 1 — walk all append(String) calls and tag the builder's root sources.
            Set<AbstractInsnNode> suspiciousRoots = new HashSet<>();
            Set<AbstractInsnNode> dynamicRoots   = new HashSet<>();

            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof MethodInsnNode m)) continue;
                if (!isStringBuilderAppend(m)) continue;
                Type[] argTypes = Type.getArgumentTypes(m.desc);
                if (argTypes.length != 1
                        || argTypes[0].getSort() != Type.OBJECT
                        || !"java/lang/String".equals(argTypes[0].getInternalName())) {
                    continue;
                }

                Frame<SourceValue> frame = frames[i];
                if (frame == null || frame.getStackSize() < 2) continue;

                // Stack layout before invoke: [..., receiver, stringArg]
                SourceValue argVal      = frame.getStack(frame.getStackSize() - 1);
                SourceValue receiverVal = frame.getStack(frame.getStackSize() - 2);

                // Follow ALOAD/ASTORE chains so constants stored in locals are found.
                Set<AbstractInsnNode> argRoots = resolveRoots(argVal, frames, insns, new HashSet<>());
                boolean suspicious = false;
                boolean dynamic    = false;
                for (AbstractInsnNode argSrc : argRoots) {
                    if (argSrc instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                        if (shouldInstrument(s)) suspicious = true;
                    } else {
                        dynamic = true;
                    }
                }

                if (suspicious || dynamic) {
                    Set<AbstractInsnNode> roots = resolveRoots(
                        receiverVal, frames, insns, new HashSet<>());
                    if (suspicious) suspiciousRoots.addAll(roots);
                    if (dynamic)    dynamicRoots.addAll(roots);
                }
            }

            // Step 2 — resolve the target toString()'s receiver roots and check.
            int toStringIdx = indexOfInsn(insns, toStringInsn);
            if (toStringIdx < 0 || frames[toStringIdx] == null) return ConcatDecision.IGNORE;

            Frame<SourceValue> tsFrame = frames[toStringIdx];
            if (tsFrame.getStackSize() == 0) return ConcatDecision.IGNORE;

            Set<AbstractInsnNode> roots = resolveRoots(
                tsFrame.getStack(tsFrame.getStackSize() - 1), frames, insns, new HashSet<>());

            for (AbstractInsnNode root : roots) {
                if (suspiciousRoots.contains(root)) return ConcatDecision.INSTRUMENT;
            }
            for (AbstractInsnNode root : roots) {
                if (dynamicRoots.contains(root)) return ConcatDecision.WARN;
            }
            return ConcatDecision.IGNORE;

        } catch (Exception ignored) {
            // Analysis failed (e.g. malformed bytecode); fall back to local scan.
            return analyzeStringBuilderChainLinear(toStringInsn);
        }
    }

    private static ConcatDecision analyzeStringBuilderChainLinear(MethodInsnNode toStringInsn) {
        boolean suspicious = false;
        boolean dynamic = false;

        for (AbstractInsnNode cursor = previousMeaningful(toStringInsn.getPrevious());
             cursor != null;
             cursor = previousMeaningful(cursor.getPrevious())) {
            if (cursor instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String stringValue) {
                if (shouldInstrument(stringValue)) {
                    suspicious = true;
                }
                continue;
            }

            if (cursor instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ALOAD) {
                dynamic = true;
                continue;
            }

            if (cursor instanceof MethodInsnNode methodInsn) {
                if (isStringBuilderAppend(methodInsn)) {
                    AbstractInsnNode argSource = previousMeaningful(methodInsn.getPrevious());
                    if (!(argSource instanceof LdcInsnNode)) {
                        dynamic = true;
                    }
                    continue;
                }

                if ("<init>".equals(methodInsn.name)
                    && "java/lang/StringBuilder".equals(methodInsn.owner)) {
                    break;
                }
            }

            if (cursor.getOpcode() == Opcodes.NEW
                && cursor instanceof TypeInsnNode typeInsn
                && "java/lang/StringBuilder".equals(typeInsn.desc)) {
                break;
            }
        }

        if (suspicious) {
            return ConcatDecision.INSTRUMENT;
        }
        if (dynamic) {
            return ConcatDecision.WARN;
        }
        return ConcatDecision.IGNORE;
    }

    /**
     * Resolves the "root" (non-LOAD) source instructions for a {@link SourceValue}
     * by following ALOAD instructions through their frame's local-variable slot.
     *
     * <p>This bridges ASTORE/ALOAD pairs: when a {@code SourceValue}'s sources
     * include an {@code ALOAD n} instruction, we look at
     * {@code frames[ALOAD_idx].getLocal(n)} to find what was stored there, and
     * recurse.  This avoids false negatives when a StringBuilder is stored in a
     * local variable between construction and use.
     */
    private static Set<AbstractInsnNode> resolveRoots(SourceValue value,
                                                       Frame<SourceValue>[] frames,
                                                       AbstractInsnNode[] insns,
                                                       Set<AbstractInsnNode> visited) {
        Set<AbstractInsnNode> roots = new HashSet<>();
        for (AbstractInsnNode src : value.insns) {
            if (!visited.add(src)) continue;
            if (src instanceof VarInsnNode varInsn && isLoadOpcode(varInsn.getOpcode())) {
                int srcIdx = indexOfInsn(insns, src);
                if (srcIdx >= 0 && frames[srcIdx] != null
                        && varInsn.var < frames[srcIdx].getLocals()) {
                    SourceValue stored = frames[srcIdx].getLocal(varInsn.var);
                    roots.addAll(resolveRoots(stored, frames, insns, visited));
                    continue;
                }
            }
            roots.add(src);
        }
        return roots;
    }

    private static boolean isLoadOpcode(int opcode) {
        return opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD
            || opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD
            || opcode == Opcodes.ALOAD;
    }

    private static int indexOfInsn(AbstractInsnNode[] insns, AbstractInsnNode target) {
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] == target) return i;
        }
        return -1;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn;
        while (cursor != null) {
            int type = cursor.getType();
            if (type != AbstractInsnNode.LABEL
                && type != AbstractInsnNode.LINE
                && type != AbstractInsnNode.FRAME) {
                return cursor;
            }
            cursor = cursor.getPrevious();
        }
        return null;
    }

    private static ConcatDecision mergeDecisions(ConcatDecision primary, ConcatDecision secondary) {
        if (primary == ConcatDecision.INSTRUMENT || secondary == ConcatDecision.INSTRUMENT) {
            return ConcatDecision.INSTRUMENT;
        }
        if (primary == ConcatDecision.WARN || secondary == ConcatDecision.WARN) {
            return ConcatDecision.WARN;
        }
        return ConcatDecision.IGNORE;
    }

    private static boolean isStringConcatFactory(InvokeDynamicInsnNode indyInsn) {
        Handle bootstrap = indyInsn.bsm;
        return bootstrap != null
            && STRING_CONCAT_FACTORY.equals(bootstrap.getOwner())
            && ("makeConcatWithConstants".equals(bootstrap.getName())
                || "makeConcat".equals(bootstrap.getName()));
    }

    private static boolean isStringBuilderToString(MethodInsnNode methodInsn) {
        return methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/StringBuilder".equals(methodInsn.owner)
            && "toString".equals(methodInsn.name)
            && "()Ljava/lang/String;".equals(methodInsn.desc);
    }

    private static boolean isStringBuilderAppend(MethodInsnNode methodInsn) {
        return methodInsn.owner.startsWith("java/lang/StringBuilder")
            && "append".equals(methodInsn.name);
    }

    private static boolean shouldInstrument(String value) {
        return value.contains("minecraft")
            || value.contains("class_")
            || value.contains("method_")
            || value.contains("field_")
            || value.startsWith("net/minecraft/")
            || value.startsWith("net.minecraft.");
    }

    /**
     * Returns {@code true} for {@code String.format(String, Object...)} and
     * {@code String.formatted(Object...)} calls.
     *
     * <p>{@code String.format} is {@code INVOKESTATIC java/lang/String.format}.
     * {@code String.formatted} is {@code INVOKEVIRTUAL java/lang/String.formatted}
     * (added in Java 15) — its receiver is the format string itself, so any
     * earlier suspicious LDC that loaded the receiver is equally detectable.
     */
    private static boolean isStringFormat(MethodInsnNode methodInsn) {
        if (!"java/lang/String".equals(methodInsn.owner)) return false;
        return ("format".equals(methodInsn.name)   && methodInsn.getOpcode() == Opcodes.INVOKESTATIC)
            || ("formatted".equals(methodInsn.name) && methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL);
    }

    /**
     * Returns {@code true} if the {@code String.format} / {@code String.formatted}
     * call's descriptor includes at least one {@code String} argument (beyond the
     * format string itself for the static variant).  Such calls may produce
     * remappable names even when the format template is not a compile-time constant.
     */
    private static boolean formatCallHasStringArgs(MethodInsnNode methodInsn) {
        Type[] args = Type.getArgumentTypes(methodInsn.desc);
        for (Type arg : args) {
            if (arg.getSort() == Type.OBJECT && "java/lang/String".equals(arg.getInternalName())) {
                return true;
            }
            // varargs Object[] argument — treat as potentially containing strings
            if (arg.getSort() == Type.ARRAY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans backward from {@code formatCall} for an {@code LDC} constant whose
     * value matches MC naming patterns.  A shallow backward scan (up to 32
     * meaningful instructions) is sufficient here because the format string is
     * always pushed as a direct argument close to the call site.
     *
     * <p>For {@code String.formatted()}, the format string is the receiver
     * ({@code this}) so it will be a few instructions earlier than for the static
     * variant where it is the first argument.
     */
    private static boolean formatCallHasSuspiciousFormatString(MethodNode method,
                                                               MethodInsnNode formatCall) {
        int limit = 32;
        AbstractInsnNode cursor = previousMeaningful(formatCall.getPrevious());
        while (cursor != null && limit-- > 0) {
            if (cursor instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                return shouldInstrument(s);
            }
            // Stop searching if we hit an instruction that terminates the argument-push chain
            int opcode = cursor.getOpcode();
            if (opcode == Opcodes.INVOKESTATIC
                || opcode == Opcodes.INVOKEVIRTUAL
                || opcode == Opcodes.INVOKESPECIAL
                || opcode == Opcodes.INVOKEINTERFACE) {
                break;
            }
            cursor = previousMeaningful(cursor.getPrevious());
        }
        return false;
    }

    private enum ConcatDecision {
        INSTRUMENT(true, false),
        WARN(false, true),
        IGNORE(false, false);

        private final boolean instrument;
        private final boolean warn;

        ConcatDecision(boolean instrument, boolean warn) {
            this.instrument = instrument;
            this.warn = warn;
        }

        boolean instrument() {
            return instrument;
        }

        boolean warn() {
            return warn;
        }
    }
}
