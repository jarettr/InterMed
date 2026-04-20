package org.intermed.core.ast;

import org.intermed.core.config.RuntimeConfig;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Resolves mixin method conflicts for a single target class.
 *
 * <p>The engine applies progressively stronger strategies:
 * <ol>
 *   <li>additive merge when there is no target conflict,</li>
 *   <li>duplicate-elision when incoming bytecode is instruction-identical,</li>
 *   <li>safe direct replace for abstract/native or obviously richer linear variants,</li>
 *   <li>semantic inline merge for linear side-effecting void methods,</li>
 *   <li>semantic contracts for render/world-state hot spots,</li>
 *   <li>priority-ordered synthetic bridge generation as the final fallback.</li>
 * </ol>
 */
public final class ResolutionEngine {

    private ResolutionEngine() {}

    public static ResolutionReport resolveMixinConflicts(ClassNode targetClass, List<ClassNode> mixins) {
        List<MixinContribution> contributions = new ArrayList<>();
        for (ClassNode mixin : mixins) {
            contributions.add(new MixinContribution(mixin.name, 1000, mixin));
        }
        return resolveMixinConflictsWithMetadata(targetClass, contributions);
    }

    public static ResolutionReport resolveMixinConflictsWithMetadata(ClassNode targetClass,
                                                                     List<MixinContribution> contributions) {
        ConflictPolicy conflictPolicy = ConflictPolicy.from(RuntimeConfig.get().getMixinConflictPolicy());
        System.out.println("[AST Engine] Analyzing " + contributions.size()
            + " mixin contribution(s) for target " + targetClass.name
            + " [policy=" + conflictPolicy.externalName + "]");

        Map<MethodKey, Integer> incomingCounts = new LinkedHashMap<>();
        Map<FieldKey, Integer> incomingFieldCounts = new LinkedHashMap<>();
        Map<FieldKey, List<String>> fieldOwners = new LinkedHashMap<>();
        java.util.Set<String> reservedSignatures = new java.util.HashSet<>();
        java.util.Set<String> reservedFieldSignatures = new java.util.HashSet<>();
        List<ConflictDetail> conflicts = new ArrayList<>();
        for (MethodNode method : targetClass.methods) {
            reservedSignatures.add(method.name + method.desc);
        }
        for (FieldNode field : targetClass.fields) {
            reservedFieldSignatures.add(field.name + field.desc);
        }
        for (MixinContribution contribution : contributions) {
            for (MethodNode method : contribution.classNode().methods) {
                if (shouldSkip(method) || isShadow(method)) {
                    continue;
                }
                MethodKey key = MethodKey.of(method);
                incomingCounts.merge(key, 1, Integer::sum);
                reservedSignatures.add(method.name + method.desc);
            }
            for (FieldNode field : contribution.classNode().fields) {
                if (isShadow(field)) {
                    continue;
                }
                FieldKey key = FieldKey.of(field);
                incomingFieldCounts.merge(key, 1, Integer::sum);
                reservedFieldSignatures.add(field.name + field.desc);
                fieldOwners.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(contribution.owner());
            }
        }

        for (MixinContribution contribution : contributions) {
            Map<MethodKey, String> renames = new LinkedHashMap<>();
            Map<FieldKey, String> fieldRenames = new LinkedHashMap<>();
            for (MethodNode method : contribution.classNode().methods) {
                if (shouldSkip(method) || isShadow(method)) {
                    continue;
                }
                if (isUnique(method)) {
                    MethodKey key = MethodKey.of(method);
                    boolean targetHas = findMethod(targetClass, key.name(), key.desc()) != null;
                    boolean duplicateIncoming = incomingCounts.getOrDefault(key, 0) > 1;
                    if (targetHas || duplicateIncoming) {
                        String newName = uniqueNameWithReserve(method.name + "$im_u", method.desc, reservedSignatures);
                        renames.put(key, newName);
                    }
                }
            }
            for (FieldNode field : contribution.classNode().fields) {
                if (isShadow(field)) {
                    continue;
                }
                FieldKey key = FieldKey.of(field);
                boolean targetHas = findField(targetClass, field.name, field.desc) != null;
                boolean duplicateIncoming = incomingFieldCounts.getOrDefault(key, 0) > 1;
                if (isUnique(field)) {
                    if (targetHas || duplicateIncoming) {
                        String newName = uniqueNameWithReserve(field.name + "$im_u", field.desc, reservedFieldSignatures);
                        fieldRenames.put(key, newName);
                    }
                } else if (targetHas || duplicateIncoming) {
                    List<String> owners = fieldOwners.getOrDefault(key, List.of());
                    failFieldConflict(targetClass, key, owners, conflicts);
                }
            }
            applyMixinSelfRewrite(contribution.classNode(), targetClass.name, renames, fieldRenames);
            for (FieldNode field : contribution.classNode().fields) {
                if (isShadow(field)) {
                    continue;
                }
                targetClass.fields.add(cloneField(field));
            }
        }

        Map<MethodKey, List<ResolvedMethod>> grouped = new LinkedHashMap<>();
        for (MixinContribution contribution : contributions) {
            int methodOrder = 0;
            for (MethodNode method : contribution.classNode().methods) {
                if (shouldSkip(method)) {
                    continue;
                }
                ConflictOperation op = classifyOperation(method);
                if (op == ConflictOperation.SHADOW) {
                    continue;
                }
                MethodKey key = MethodKey.of(method);
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new ResolvedMethod(
                        contribution.owner(),
                        contribution.priority(),
                        contribution.applicationOrder(),
                        methodOrder++,
                        op,
                        method
                    ));
            }
        }

        int modifiedMethods = 0;
        int addedMethods = 0;
        int bridgeMethods = 0;
        int directReplacements = 0;
        int semanticMerges = 0;
        int deduplicatedMethods = 0;

        for (Map.Entry<MethodKey, List<ResolvedMethod>> entry : grouped.entrySet()) {
            MethodKey key = entry.getKey();
            MethodNode targetMethod = findMethod(targetClass, key.name(), key.desc());

            List<ResolvedMethod> methods = entry.getValue().stream()
                .sorted(resolvedMethodComparator())
                .toList();

            if (targetMethod == null) {
                List<ResolvedMethod> accessors = methods.stream()
                    .filter(m -> m.operation() == ConflictOperation.ACCESSOR
                        || m.operation() == ConflictOperation.INVOKER)
                    .toList();
                if (!accessors.isEmpty()) {
                    if (methods.size() > accessors.size() || accessors.size() > 1) {
                        failAccessorConflict(targetClass, key, methods, conflicts);
                    }
                    MethodNode generated = generateAccessorOrInvoker(targetClass, accessors.get(0), conflicts);
                    targetClass.methods.add(generated);
                    addedMethods++;
                    continue;
                }
                // Track names already added in this iteration so that two INJECT_LIKE handlers
                // from different mixin classes with the same name+descriptor do not produce
                // duplicate methods in the class file (JVM verifier rejects those).
                // INJECT_LIKE methods are discovered by the Mixin runtime via their @Inject /
                // @Redirect annotations — renaming the handler does not break Mixin processing.
                java.util.Set<String> addedSigs = new java.util.HashSet<>();
                for (ResolvedMethod incoming : dedupeIncoming(methods)) {
                    if (incoming.operation() == ConflictOperation.OVERWRITE) {
                        System.err.println("[AST Engine] Skipping @Overwrite from " + incoming.owner()
                            + " because target method " + key.name() + key.desc() + " does not exist");
                        continue;
                    }
                    MethodNode clone = cloneMethod(incoming.method());
                    if (incoming.operation() == ConflictOperation.INJECT_LIKE) {
                        String sig = clone.name + clone.desc;
                        if (!addedSigs.add(sig)) {
                            // A handler with this exact name+desc was already added from another
                            // mixin class — give this copy a unique synthetic name.
                            clone.name = uniqueName(targetClass, clone.name + "$im");
                            addedSigs.add(clone.name + clone.desc);
                        }
                    }
                    targetClass.methods.add(clone);
                    addedMethods++;
                }
                continue;
            }

            List<ResolvedMethod> accessors = methods.stream()
                .filter(m -> m.operation() == ConflictOperation.ACCESSOR
                    || m.operation() == ConflictOperation.INVOKER)
                .toList();
            if (!accessors.isEmpty()) {
                failAccessorConflict(targetClass, key, methods, conflicts);
            }

            DedupeResult dedupeResult = dedupeAgainstTarget(targetMethod, methods);
            List<ResolvedMethod> uniqueIncoming = dedupeResult.uniqueMethods();
            deduplicatedMethods += dedupeResult.elidedCount();

            if (uniqueIncoming.isEmpty()) {
                continue;
            }

            List<String> contributors = describeContributors(uniqueIncoming);
            SemanticContractRegistry.SemanticContract semanticContract =
                SemanticContractRegistry.resolve(
                    targetClass.name,
                    key.name(),
                    key.desc(),
                    targetMethod,
                    uniqueIncoming.stream().map(ResolvedMethod::method).toList()
                );

            if (canDirectlyReplace(targetMethod) && uniqueIncoming.size() == 1) {
                replaceMethodBody(targetMethod, uniqueIncoming.get(0).method());
                modifiedMethods++;
                directReplacements++;
                continue;
            }

            if (uniqueIncoming.size() == 1) {
                MethodNode incomingMethod = uniqueIncoming.get(0).method();
                if (!semanticContract.isCritical() && tryLinearSupersetMerge(targetMethod, incomingMethod)) {
                    modifiedMethods++;
                    semanticMerges++;
                    continue;
                }

                if (isSafeOverwrite(targetMethod, incomingMethod)) {
                    replaceMethodBody(targetMethod, incomingMethod);
                    modifiedMethods++;
                    directReplacements++;
                    continue;
                }

                if (semanticContract.isCritical() && conflictPolicy != ConflictPolicy.FAIL) {
                    SemanticResolution semanticResolution = applySemanticContract(
                        targetClass,
                        key,
                        targetMethod,
                        uniqueIncoming,
                        semanticContract
                    );
                    modifiedMethods += semanticResolution.modifiedMethods();
                    addedMethods += semanticResolution.addedMethods();
                    directReplacements += semanticResolution.directReplacements();
                    conflicts.add(new ConflictDetail(
                        targetClass.name, key.name(), key.desc(), contributors,
                        semanticResolution.resolution(), null));
                    continue;
                }
            }

            if (semanticContract.isCritical() && conflictPolicy != ConflictPolicy.FAIL) {
                SemanticResolution semanticResolution = applySemanticContract(
                    targetClass,
                    key,
                    targetMethod,
                    uniqueIncoming,
                    semanticContract
                );
                modifiedMethods += semanticResolution.modifiedMethods();
                addedMethods += semanticResolution.addedMethods();
                directReplacements += semanticResolution.directReplacements();
                conflicts.add(new ConflictDetail(
                    targetClass.name, key.name(), key.desc(), contributors,
                    semanticResolution.resolution(), null));
                continue;
            }

            if (tryInlineSemanticMerge(targetMethod, uniqueIncoming)) {
                modifiedMethods++;
                semanticMerges++;
                continue;
            }

            // INJECT_LIKE handlers (@Inject, @Redirect, @ModifyArg, …) are additive by design:
            // the Mixin framework processes each independently via its annotation — not by name.
            // When targetMethod already exists (e.g. from an earlier mixin class merged into this
            // target), keep it and add the remaining INJECT_LIKE methods as uniquely-named copies.
            if (allInjectLike(uniqueIncoming)) {
                for (ResolvedMethod incoming : uniqueIncoming) {
                    MethodNode clone = cloneMethod(incoming.method());
                    clone.name = uniqueName(targetClass, clone.name + "$im");
                    targetClass.methods.add(clone);
                    addedMethods++;
                }
                conflicts.add(new ConflictDetail(
                    targetClass.name, key.name(), key.desc(),
                    uniqueIncoming.stream()
                        .map(m -> m.owner() + " (op=" + m.operation() + ")")
                        .toList(),
                    "ADDITIVE_INJECT", null));
                continue;
            }

            switch (conflictPolicy) {
                case OVERWRITE -> {
                    replaceMethodBody(targetMethod, uniqueIncoming.get(0).method());
                    modifiedMethods++;
                    directReplacements++;
                    conflicts.add(new ConflictDetail(
                        targetClass.name, key.name(), key.desc(), contributors,
                        "OVERWRITE", null));
                }
                case FAIL -> {
                    String failMsg = buildConflictMessage(targetClass.name, key, contributors);
                    conflicts.add(new ConflictDetail(
                        targetClass.name, key.name(), key.desc(), contributors,
                        "FAIL", failMsg));
                    throw new MixinConflictException(failMsg, conflicts);
                }
                case BRIDGE, MERGE -> {
                    generatePriorityBridge(targetClass, targetMethod, uniqueIncoming);
                    modifiedMethods++;
                    bridgeMethods++;
                    conflicts.add(new ConflictDetail(
                        targetClass.name, key.name(), key.desc(), contributors,
                        "BRIDGE", null));
                }
            }
        }

        return new ResolutionReport(
            modifiedMethods,
            addedMethods,
            bridgeMethods,
            directReplacements,
            semanticMerges,
            deduplicatedMethods,
            Collections.unmodifiableList(conflicts)
        );
    }

    private static ConflictOperation classifyOperation(MethodNode method) {
        if (hasAnnotation(method, "/Shadow;")) {
            return ConflictOperation.SHADOW;
        }
        if (hasAnnotation(method, "/Accessor;")) {
            return ConflictOperation.ACCESSOR;
        }
        if (hasAnnotation(method, "/Invoker;")) {
            return ConflictOperation.INVOKER;
        }
        if (hasAnnotation(method, "/Overwrite;")) {
            return ConflictOperation.OVERWRITE;
        }
        if (hasAnnotation(method, "/Inject;")
            || hasAnnotation(method, "/Redirect;")
            || hasAnnotation(method, "/ModifyArg;")
            || hasAnnotation(method, "/ModifyArgs;")
            || hasAnnotation(method, "/ModifyVariable;")
            || hasAnnotation(method, "/ModifyConstant;")
            || hasAnnotation(method, "/ModifyExpressionValue;")
            || hasAnnotation(method, "/ModifyReturnValue;")
            || hasAnnotation(method, "/ModifyReceiver;")
            || hasAnnotation(method, "/WrapOperation;")
            || hasAnnotation(method, "/WrapMethod;")
            || hasAnnotation(method, "/WrapWithCondition;")) {
            return ConflictOperation.INJECT_LIKE;
        }
        return ConflictOperation.PLAIN;
    }

    private static List<ResolvedMethod> dedupeIncoming(List<ResolvedMethod> methods) {
        Map<String, ResolvedMethod> unique = new LinkedHashMap<>();
        for (ResolvedMethod method : methods) {
            unique.putIfAbsent(fingerprint(method.method()), method);
        }
        return new ArrayList<>(unique.values());
    }

    private static DedupeResult dedupeAgainstTarget(MethodNode targetMethod,
                                                    List<ResolvedMethod> incoming) {
        String targetFingerprint = fingerprint(targetMethod);
        Map<String, ResolvedMethod> unique = new LinkedHashMap<>();
        int elidedCount = 0;
        for (ResolvedMethod method : incoming) {
            String methodFingerprint = fingerprint(method.method());
            if (targetFingerprint.equals(methodFingerprint)) {
                elidedCount++;
                continue;
            }
            if (unique.putIfAbsent(methodFingerprint, method) != null) {
                elidedCount++;
            }
        }
        return new DedupeResult(new ArrayList<>(unique.values()), elidedCount);
    }

    private static boolean canDirectlyReplace(MethodNode targetMethod) {
        return (targetMethod.access & Opcodes.ACC_ABSTRACT) != 0
            || (targetMethod.access & Opcodes.ACC_NATIVE) != 0;
    }

    private static boolean isSafeOverwrite(MethodNode targetMethod, MethodNode incomingMethod) {
        return instructionCount(targetMethod) <= 2
            && instructionCount(incomingMethod) > instructionCount(targetMethod);
    }

    private static boolean tryLinearSupersetMerge(MethodNode targetMethod, MethodNode incomingMethod) {
        if (!isLinearMethod(targetMethod) || !isLinearMethod(incomingMethod)) {
            return false;
        }

        List<String> targetBody = normalizedInstructionTokens(targetMethod, true);
        List<String> incomingBody = normalizedInstructionTokens(incomingMethod, true);
        if (targetBody.isEmpty() || incomingBody.isEmpty()) {
            return false;
        }

        if (incomingBody.size() <= targetBody.size()) {
            return false;
        }

        if (isSubsequenceAt(targetBody, incomingBody, 0)
            || isSubsequenceAt(targetBody, incomingBody, incomingBody.size() - targetBody.size())) {
            replaceMethodBody(targetMethod, incomingMethod);
            return true;
        }

        return false;
    }

    private static Comparator<ResolvedMethod> resolvedMethodComparator() {
        return Comparator
            .comparingInt(ResolvedMethod::priority)
            .reversed()
            .thenComparingInt(ResolvedMethod::applicationOrder)
            .thenComparingInt(ResolvedMethod::methodOrder);
    }

    private static List<String> describeContributors(List<ResolvedMethod> methods) {
        return methods.stream()
            .sorted(resolvedMethodComparator())
            .map(m -> m.owner()
                + " (priority=" + m.priority()
                + ", order=" + m.applicationOrder()
                + ", op=" + m.operation() + ")")
            .toList();
    }

    private static SemanticResolution applySemanticContract(ClassNode targetClass,
                                                            MethodKey key,
                                                            MethodNode targetMethod,
                                                            List<ResolvedMethod> incomingMethods,
                                                            SemanticContractRegistry.SemanticContract contract) {
        List<ResolvedMethod> ordered = incomingMethods.stream()
            .sorted(resolvedMethodComparator())
            .toList();
        String zone = contract.zone().name();

        if (allInjectLike(ordered)) {
            int addedMethods = 0;
            int queueIndex = 0;
            for (ResolvedMethod incoming : ordered) {
                MethodNode clone = cloneMethod(incoming.method());
                clone.name = uniqueName(targetClass, key.name() + "$intermed_queue$" + queueIndex++);
                markSynthetic(clone);
                targetClass.methods.add(clone);
                addedMethods++;
            }
            return new SemanticResolution(
                0,
                addedMethods,
                0,
                "SEMANTIC_PRIORITY_QUEUE_" + zone
            );
        }

        MethodNode originalSnapshot = cloneMethod(targetMethod);
        originalSnapshot.name = uniqueName(targetClass, key.name() + "$intermed_orig");
        markSynthetic(originalSnapshot);
        targetClass.methods.add(originalSnapshot);

        ResolvedMethod winner = ordered.getFirst();
        replaceMethodBody(targetMethod, winner.method());

        int addedMethods = 1;
        int isolatedIndex = 0;
        for (int i = 1; i < ordered.size(); i++) {
            MethodNode clone = cloneMethod(ordered.get(i).method());
            clone.name = uniqueName(targetClass, key.name() + "$intermed_isolated$" + isolatedIndex++);
            markSynthetic(clone);
            targetClass.methods.add(clone);
            addedMethods++;
        }

        return new SemanticResolution(
            1,
            addedMethods,
            1,
            "SEMANTIC_ISOLATION_" + zone
        );
    }

    private static boolean tryInlineSemanticMerge(MethodNode targetMethod,
                                                  List<ResolvedMethod> incomingMethods) {
        if (!allowsSemanticMerge(incomingMethods)) {
            return false;
        }
        if (Type.getReturnType(targetMethod.desc).getSort() != Type.VOID) {
            return false;
        }
        if (!isAppendable(targetMethod)) {
            return false;
        }
        for (ResolvedMethod incoming : incomingMethods) {
            if (!isAppendable(incoming.method())) {
                return false;
            }
        }

        List<MethodNode> ordered = incomingMethods.stream()
            .sorted(resolvedMethodComparator())
            .map(ResolvedMethod::method)
            .toList();

        MethodNode merged = cloneMethod(targetMethod);
        merged.instructions.clear();

        appendMethodBodyWithoutTerminalReturn(merged.instructions, targetMethod);
        for (MethodNode incoming : ordered) {
            appendMethodBodyWithoutTerminalReturn(merged.instructions, incoming);
        }
        merged.instructions.add(new InsnNode(Opcodes.RETURN));

        merged.tryCatchBlocks = new ArrayList<>();
        merged.maxLocals = maxLocalCount(targetMethod, ordered);
        merged.maxStack = maxStackCount(targetMethod, ordered);

        replaceMethodBody(targetMethod, merged);
        return true;
    }

    private static boolean allowsSemanticMerge(List<ResolvedMethod> incomingMethods) {
        return incomingMethods.stream().noneMatch(method -> method.operation() == ConflictOperation.OVERWRITE);
    }

    /** Returns {@code true} if every incoming method carries an INJECT_LIKE annotation. */
    private static boolean allInjectLike(List<ResolvedMethod> incomingMethods) {
        return !incomingMethods.isEmpty()
            && incomingMethods.stream().allMatch(m -> m.operation() == ConflictOperation.INJECT_LIKE);
    }

    private static boolean isAppendable(MethodNode method) {
        return isLinearMethod(method)
            && Type.getReturnType(method.desc).getSort() == Type.VOID;
    }

    private static boolean isLinearMethod(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof JumpInsnNode
                || insn instanceof TableSwitchInsnNode
                || insn instanceof LookupSwitchInsnNode) {
                return false;
            }
            if (insn.getOpcode() == Opcodes.ATHROW
                || insn.getOpcode() == Opcodes.MONITORENTER
                || insn.getOpcode() == Opcodes.MONITOREXIT) {
                return false;
            }
        }
        return true;
    }

    private static void replaceMethodBody(MethodNode targetMethod, MethodNode incomingMethod) {
        ClonedInstructions cloned = cloneInstructionsWithLabels(incomingMethod.instructions);
        targetMethod.instructions = cloned.instructions();
        targetMethod.tryCatchBlocks = cloneTryCatchBlocks(incomingMethod.tryCatchBlocks, cloned.labelMap());
        targetMethod.maxLocals = incomingMethod.maxLocals;
        targetMethod.maxStack = incomingMethod.maxStack;
        targetMethod.access = mergeAccess(targetMethod.access, incomingMethod.access);
    }

    private static void generatePriorityBridge(ClassNode targetClass,
                                               MethodNode originalMethod,
                                               List<ResolvedMethod> incomingMethods) {
        System.out.println("[AST Engine] Conflict on " + targetClass.name + "."
            + originalMethod.name + " - generating priority bridge");

        String bridgeName = originalMethod.name;
        String originalRenamed = uniqueName(targetClass, bridgeName + "$intermed_orig");
        originalMethod.name = originalRenamed;

        List<MethodNode> orderedMethods = new ArrayList<>();
        orderedMethods.add(originalMethod);

        int index = 0;
        for (ResolvedMethod incoming : incomingMethods) {
            MethodNode clone = cloneMethod(incoming.method());
            clone.name = uniqueName(targetClass, bridgeName + "$intermed_mixin$" + index++);
            targetClass.methods.add(clone);
            orderedMethods.add(clone);
        }

        MethodNode bridge = new MethodNode(
            bridgeAccess(originalMethod.access),
            bridgeName,
            originalMethod.desc,
            originalMethod.signature,
            originalMethod.exceptions == null ? null : originalMethod.exceptions.toArray(String[]::new)
        );

        bridge.instructions = buildBridgeBody(
            targetClass.name,
            orderedMethods,
            originalMethod.desc,
            (originalMethod.access & Opcodes.ACC_STATIC) != 0
        );
        targetClass.methods.add(bridge);
    }

    private static InsnList buildBridgeBody(String owner, List<MethodNode> orderedMethods,
                                            String descriptor, boolean isStatic) {
        InsnList instructions = new InsnList();
        Type[] args = Type.getArgumentTypes(descriptor);
        Type returnType = Type.getReturnType(descriptor);
        int invokeOpcode = isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;

        for (int i = 0; i < orderedMethods.size(); i++) {
            MethodNode method = orderedMethods.get(i);
            loadArgs(instructions, args, isStatic);
            instructions.add(new MethodInsnNode(invokeOpcode, owner, method.name, descriptor, false));

            boolean lastInvocation = i == orderedMethods.size() - 1;
            if (!lastInvocation && returnType.getSort() != Type.VOID) {
                instructions.add(new InsnNode(returnType.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
            }
        }

        instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        return instructions;
    }

    private static void loadArgs(InsnList instructions, Type[] args, boolean isStatic) {
        if (!isStatic) {
            instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }

        int localIndex = isStatic ? 0 : 1;
        for (Type arg : args) {
            instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), localIndex));
            localIndex += arg.getSize();
        }
    }

    private static int bridgeAccess(int originalAccess) {
        int access = originalAccess & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        return access | Opcodes.ACC_SYNTHETIC;
    }

    private static void markSynthetic(MethodNode method) {
        method.access |= Opcodes.ACC_SYNTHETIC;
    }

    private static int mergeAccess(int targetAccess, int incomingAccess) {
        int preservedVisibility = targetAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
        int incomingFlags = incomingAccess & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
        return preservedVisibility | incomingFlags;
    }

    private static String uniqueName(ClassNode targetClass, String base) {
        String candidate = base;
        int counter = 0;
        while (findMethod(targetClass, candidate, null) != null) {
            candidate = base + "$" + counter++;
        }
        return candidate;
    }

    private static MethodNode findMethod(ClassNode clazz, String name, String desc) {
        for (MethodNode method : clazz.methods) {
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) {
                return method;
            }
        }
        return null;
    }

    private static boolean shouldSkip(MethodNode method) {
        return "<init>".equals(method.name) || "<clinit>".equals(method.name);
    }

    private static boolean isShadow(MethodNode method) {
        return hasAnnotation(method, "/Shadow;");
    }

    private static boolean isShadow(FieldNode field) {
        return hasAnnotation(field, "/Shadow;");
    }

    private static boolean isUnique(MethodNode method) {
        return hasAnnotation(method, "/Unique;");
    }

    private static boolean isUnique(FieldNode field) {
        return hasAnnotation(field, "/Unique;");
    }

    private static boolean hasAnnotation(MethodNode method, String suffix) {
        if (method == null || suffix == null) {
            return false;
        }
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (annotation.desc != null && annotation.desc.endsWith(suffix)) {
                    return true;
                }
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode annotation : method.invisibleAnnotations) {
                if (annotation.desc != null && annotation.desc.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnnotation(FieldNode field, String suffix) {
        if (field == null || suffix == null) {
            return false;
        }
        if (field.visibleAnnotations != null) {
            for (AnnotationNode annotation : field.visibleAnnotations) {
                if (annotation.desc != null && annotation.desc.endsWith(suffix)) {
                    return true;
                }
            }
        }
        if (field.invisibleAnnotations != null) {
            for (AnnotationNode annotation : field.invisibleAnnotations) {
                if (annotation.desc != null && annotation.desc.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String uniqueNameWithReserve(String base, String desc, java.util.Set<String> reserved) {
        String candidate = base;
        int counter = 0;
        while (reserved.contains(candidate + desc)) {
            candidate = base + "$" + counter++;
        }
        reserved.add(candidate + desc);
        return candidate;
    }

    private static void applyMixinSelfRewrite(ClassNode mixin,
                                              String targetInternalName,
                                              Map<MethodKey, String> renames,
                                              Map<FieldKey, String> fieldRenames) {
        if (mixin == null) {
            return;
        }
        String mixinName = mixin.name;
        if (mixinName == null) {
            return;
        }
        Map<MethodKey, String> methodRenameMap = renames == null ? Map.of() : renames;
        Map<FieldKey, String> fieldRenameMap = fieldRenames == null ? Map.of() : fieldRenames;
        for (FieldNode field : mixin.fields) {
            FieldKey key = FieldKey.of(field);
            String renamed = fieldRenameMap.get(key);
            if (renamed != null) {
                field.name = renamed;
            }
        }
        for (MethodNode method : mixin.methods) {
            MethodKey key = MethodKey.of(method);
            String renamed = methodRenameMap.get(key);
            if (renamed != null) {
                method.name = renamed;
            }
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode methodInsn) {
                    if (mixinName.equals(methodInsn.owner)) {
                        methodInsn.owner = targetInternalName;
                        MethodKey callKey = new MethodKey(methodInsn.name, methodInsn.desc);
                        String callRename = methodRenameMap.get(callKey);
                        if (callRename != null) {
                            methodInsn.name = callRename;
                        }
                    }
                } else if (insn instanceof FieldInsnNode fieldInsn) {
                    if (mixinName.equals(fieldInsn.owner)) {
                        fieldInsn.owner = targetInternalName;
                        FieldKey fieldKey = new FieldKey(fieldInsn.name, fieldInsn.desc);
                        String fieldRename = fieldRenameMap.get(fieldKey);
                        if (fieldRename != null) {
                            fieldInsn.name = fieldRename;
                        }
                    }
                }
            }
        }
    }

    private static FieldNode cloneField(FieldNode source) {
        FieldNode clone = new FieldNode(
            source.access,
            source.name,
            source.desc,
            source.signature,
            source.value
        );
        if (source.visibleAnnotations != null) {
            clone.visibleAnnotations = new ArrayList<>(source.visibleAnnotations);
        }
        if (source.invisibleAnnotations != null) {
            clone.invisibleAnnotations = new ArrayList<>(source.invisibleAnnotations);
        }
        if (source.visibleTypeAnnotations != null) {
            clone.visibleTypeAnnotations = new ArrayList<>(source.visibleTypeAnnotations);
        }
        if (source.invisibleTypeAnnotations != null) {
            clone.invisibleTypeAnnotations = new ArrayList<>(source.invisibleTypeAnnotations);
        }
        if (source.attrs != null) {
            clone.attrs = new ArrayList<>(source.attrs);
        }
        return clone;
    }

    private static MethodNode cloneMethod(MethodNode source) {
        MethodNode clone = new MethodNode(
            source.access,
            source.name,
            source.desc,
            source.signature,
            source.exceptions == null ? null : source.exceptions.toArray(String[]::new)
        );
        source.accept(clone);
        return clone;
    }

    private static ClonedInstructions cloneInstructionsWithLabels(InsnList source) {
        Map<LabelNode, LabelNode> labels = buildLabelMap(source);
        InsnList clone = new InsnList();
        for (AbstractInsnNode insn : source.toArray()) {
            clone.add(insn.clone(labels));
        }
        return new ClonedInstructions(clone, labels);
    }

    private static List<TryCatchBlockNode> cloneTryCatchBlocks(List<TryCatchBlockNode> source,
                                                               Map<LabelNode, LabelNode> labelMap) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<TryCatchBlockNode> clone = new ArrayList<>(source.size());
        for (TryCatchBlockNode block : source) {
            clone.add(new TryCatchBlockNode(
                labelMap.getOrDefault(block.start, block.start),
                labelMap.getOrDefault(block.end, block.end),
                block.handler == null ? null : labelMap.getOrDefault(block.handler, block.handler),
                block.type
            ));
        }
        return clone;
    }

    private static void appendMethodBodyWithoutTerminalReturn(InsnList target, MethodNode sourceMethod) {
        InsnList source = sourceMethod.instructions;
        AbstractInsnNode[] nodes = source.toArray();
        int limit = nodes.length;
        while (limit > 0 && isTerminalReturn(nodes[limit - 1])) {
            limit--;
        }
        Map<LabelNode, LabelNode> labels = buildLabelMap(source);
        for (int i = 0; i < limit; i++) {
            target.add(nodes[i].clone(labels));
        }
    }

    private static boolean isTerminalReturn(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode == Opcodes.RETURN
            || opcode == Opcodes.ARETURN
            || opcode == Opcodes.IRETURN
            || opcode == Opcodes.FRETURN
            || opcode == Opcodes.LRETURN
            || opcode == Opcodes.DRETURN;
    }

    private static Map<LabelNode, LabelNode> buildLabelMap(InsnList instructions) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : instructions.toArray()) {
            if (insn instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        return labels;
    }

    private static int maxLocalCount(MethodNode targetMethod, Collection<MethodNode> methods) {
        int max = targetMethod.maxLocals;
        for (MethodNode method : methods) {
            max = Math.max(max, method.maxLocals);
        }
        return max;
    }

    private static int maxStackCount(MethodNode targetMethod, Collection<MethodNode> methods) {
        int max = targetMethod.maxStack;
        for (MethodNode method : methods) {
            max = Math.max(max, method.maxStack);
        }
        return max;
    }

    private static int instructionCount(MethodNode method) {
        return method.instructions == null ? 0 : method.instructions.size();
    }

    private static String fingerprint(MethodNode method) {
        return method.desc + "::" + String.join("|", normalizedInstructionTokens(method, false));
    }

    private static List<String> normalizedInstructionTokens(MethodNode method, boolean skipTerminalReturn) {
        List<String> tokens = new ArrayList<>();
        AbstractInsnNode[] instructions = method.instructions == null ? new AbstractInsnNode[0] : method.instructions.toArray();
        int limit = instructions.length;
        if (skipTerminalReturn) {
            while (limit > 0 && isStructuralNode(instructions[limit - 1])) {
                limit--;
            }
            while (limit > 0 && isTerminalReturn(instructions[limit - 1])) {
                limit--;
            }
        }

        for (int i = 0; i < limit; i++) {
            AbstractInsnNode insn = instructions[i];
            if (isStructuralNode(insn)) {
                continue;
            }
            tokens.add(insnToken(insn));
        }
        return tokens;
    }

    private static boolean isStructuralNode(AbstractInsnNode insn) {
        return insn instanceof LabelNode
            || insn instanceof LineNumberNode
            || insn.getOpcode() < 0;
    }

    private static boolean isSubsequenceAt(List<String> expected, List<String> actual, int startIndex) {
        if (startIndex < 0 || startIndex + expected.size() > actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(startIndex + i))) {
                return false;
            }
        }
        return true;
    }

    private static String insnToken(AbstractInsnNode insn) {
        String constantToken = constantLoadToken(insn);
        if (constantToken != null) {
            return constantToken;
        }
        return switch (insn) {
            case VarInsnNode varInsn -> "VAR:" + varInsn.getOpcode() + ":" + varInsn.var;
            case MethodInsnNode methodInsn -> "CALL:" + methodInsn.getOpcode() + ":" + methodInsn.owner
                + ":" + methodInsn.name + ":" + methodInsn.desc + ":" + methodInsn.itf;
            case FieldInsnNode fieldInsn -> "FIELD:" + fieldInsn.getOpcode() + ":" + fieldInsn.owner
                + ":" + fieldInsn.name + ":" + fieldInsn.desc;
            case TypeInsnNode typeInsn -> "TYPE:" + typeInsn.getOpcode() + ":" + typeInsn.desc;
            case LdcInsnNode ldcInsn -> "LDC:" + String.valueOf(ldcInsn.cst);
            case IntInsnNode intInsn -> "INT:" + intInsn.getOpcode() + ":" + intInsn.operand;
            case IincInsnNode iincInsn -> "IINC:" + iincInsn.var + ":" + iincInsn.incr;
            case JumpInsnNode jumpInsn -> "JUMP:" + jumpInsn.getOpcode();
            case InvokeDynamicInsnNode indyInsn -> "INDY:" + indyInsn.name + ":" + indyInsn.desc;
            case MultiANewArrayInsnNode multiArrayInsn -> "MULTI:" + multiArrayInsn.desc + ":" + multiArrayInsn.dims;
            case InsnNode simpleInsn -> "INSN:" + simpleInsn.getOpcode();
            default -> insn.getClass().getSimpleName() + ":" + insn.getOpcode();
        };
    }

    private static String constantLoadToken(AbstractInsnNode insn) {
        if (insn instanceof IntInsnNode intInsn
            && (intInsn.getOpcode() == Opcodes.BIPUSH || intInsn.getOpcode() == Opcodes.SIPUSH)) {
            return "CONST:int:" + intInsn.operand;
        }
        if (insn instanceof LdcInsnNode ldcInsn) {
            Object constant = ldcInsn.cst;
            if (constant instanceof Integer value) {
                return "CONST:int:" + value;
            }
            if (constant instanceof Long value) {
                return "CONST:long:" + value;
            }
            if (constant instanceof Float value) {
                return "CONST:float:" + Float.toString(value);
            }
            if (constant instanceof Double value) {
                return "CONST:double:" + Double.toString(value);
            }
            if (constant instanceof Character value) {
                return "CONST:int:" + (int) value.charValue();
            }
            if (constant instanceof String value) {
                return "CONST:string:" + value;
            }
            if (constant instanceof Type value) {
                return "CONST:type:" + value.getDescriptor();
            }
            return null;
        }
        return switch (insn.getOpcode()) {
            case Opcodes.ACONST_NULL -> "CONST:null";
            case Opcodes.ICONST_M1 -> "CONST:int:-1";
            case Opcodes.ICONST_0 -> "CONST:int:0";
            case Opcodes.ICONST_1 -> "CONST:int:1";
            case Opcodes.ICONST_2 -> "CONST:int:2";
            case Opcodes.ICONST_3 -> "CONST:int:3";
            case Opcodes.ICONST_4 -> "CONST:int:4";
            case Opcodes.ICONST_5 -> "CONST:int:5";
            case Opcodes.LCONST_0 -> "CONST:long:0";
            case Opcodes.LCONST_1 -> "CONST:long:1";
            case Opcodes.FCONST_0 -> "CONST:float:0.0";
            case Opcodes.FCONST_1 -> "CONST:float:1.0";
            case Opcodes.FCONST_2 -> "CONST:float:2.0";
            case Opcodes.DCONST_0 -> "CONST:double:0.0";
            case Opcodes.DCONST_1 -> "CONST:double:1.0";
            default -> null;
        };
    }

    private enum ConflictOperation {
        PLAIN,
        INJECT_LIKE,
        OVERWRITE,
        ACCESSOR,
        INVOKER,
        SHADOW
    }

    private record MethodKey(String name, String desc) {
        static MethodKey of(MethodNode method) {
            return new MethodKey(method.name, method.desc);
        }
    }

    private record FieldKey(String name, String desc) {
        static FieldKey of(FieldNode field) {
            return new FieldKey(field.name, field.desc);
        }
    }

    public record MixinContribution(String owner, int priority, int applicationOrder, ClassNode classNode) {
        public MixinContribution {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(classNode, "classNode");
        }

        public MixinContribution(String owner, int priority, ClassNode classNode) {
            this(owner, priority, Integer.MAX_VALUE, classNode);
        }
    }

    private record ResolvedMethod(String owner,
                                  int priority,
                                  int applicationOrder,
                                  int methodOrder,
                                  ConflictOperation operation,
                                  MethodNode method) {
        private ResolvedMethod(String owner, int priority, ConflictOperation operation, MethodNode method) {
            this(owner, priority, Integer.MAX_VALUE, Integer.MAX_VALUE, operation, method);
        }
    }

    private record DedupeResult(List<ResolvedMethod> uniqueMethods, int elidedCount) {}

    private record SemanticResolution(int modifiedMethods,
                                      int addedMethods,
                                      int directReplacements,
                                      String resolution) {}

    private enum ConflictPolicy {
        BRIDGE("bridge"),
        MERGE("merge"),
        OVERWRITE("overwrite"),
        FAIL("fail");

        private final String externalName;

        ConflictPolicy(String externalName) {
            this.externalName = externalName;
        }

        private static ConflictPolicy from(String configured) {
            if (configured == null || configured.isBlank()) {
                return BRIDGE;
            }
            return switch (configured.trim().toLowerCase()) {
                case "merge" -> MERGE;
                case "overwrite" -> OVERWRITE;
                case "fail", "error", "strict" -> FAIL;
                default -> BRIDGE;
            };
        }
    }

    private record ClonedInstructions(InsnList instructions, Map<LabelNode, LabelNode> labelMap) {}

    /**
     * Per-method conflict detail for user-facing reports.
     *
     * @param targetClass  Internal class name of the target.
     * @param methodName   Simple method name.
     * @param methodDesc   JVM descriptor of the method.
     * @param contributors Human-readable list of contributing mixin owners.
     * @param resolution   One of {@code BRIDGE}, {@code MERGE}, {@code OVERWRITE}, {@code FAIL}.
     * @param failureReason Non-null only when {@code resolution} is {@code FAIL}.
     */
    public record ConflictDetail(
        String       targetClass,
        String       methodName,
        String       methodDesc,
        List<String> contributors,
        String       resolution,
        String       failureReason
    ) {
        /** Human-readable one-liner suitable for printing in a user report. */
        public String summary() {
            String base = "[" + resolution + "] "
                + targetClass.replace('/', '.') + "#" + methodName + methodDesc
                + " ← " + String.join(", ", contributors);
            return failureReason != null ? base + "\n  ↳ Reason: " + failureReason : base;
        }
    }

    /**
     * Thrown when {@link ConflictPolicy#FAIL} is in effect and an unresolvable
     * conflict is detected.  Carries the full list of {@link ConflictDetail}
     * records accumulated so far so callers can emit a complete conflict report.
     */
    public static final class MixinConflictException extends IllegalStateException {
        private final List<ConflictDetail> conflicts;

        public MixinConflictException(String message, List<ConflictDetail> conflicts) {
            super(message);
            this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
        }

        /** All conflict details recorded up to (and including) the failing one. */
        public List<ConflictDetail> getConflicts() {
            return conflicts;
        }

        /** Formats a full user-facing report listing every conflict. */
        public String conflictReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("═══ InterMed Mixin Conflict Report ═══\n");
            for (ConflictDetail detail : conflicts) {
                sb.append("  • ").append(detail.summary()).append('\n');
            }
            sb.append("══════════════════════════════════════");
            return sb.toString();
        }
    }

    private static String buildConflictMessage(String targetClass, MethodKey key,
                                               List<String> contributors) {
        return "Unresolvable mixin conflict on "
            + targetClass.replace('/', '.') + "#" + key.name() + key.desc()
            + " — contributors: " + String.join("; ", contributors);
    }

    private static void failAccessorConflict(ClassNode targetClass,
                                             MethodKey key,
                                             List<ResolvedMethod> methods,
                                             List<ConflictDetail> conflicts) {
        List<String> contributors = methods.stream()
            .map(m -> m.owner() + " (priority=" + m.priority() + ", op=" + m.operation() + ")")
            .toList();
        String message = "Accessor/Invoker conflict on "
            + targetClass.name.replace('/', '.') + "#" + key.name() + key.desc()
            + " — accessors must be unique and must not collide with target methods";
        conflicts.add(new ConflictDetail(
            targetClass.name, key.name(), key.desc(), contributors, "FAIL", message));
        throw new MixinConflictException(message, conflicts);
    }

    private static void failFieldConflict(ClassNode targetClass,
                                          FieldKey key,
                                          List<String> owners,
                                          List<ConflictDetail> conflicts) {
        List<String> contributors = owners.isEmpty()
            ? List.of("unknown")
            : owners.stream().map(owner -> owner + " (field)").toList();
        String message = "Field conflict on "
            + targetClass.name.replace('/', '.') + "#" + key.name() + ":" + key.desc()
            + " — contributors: " + String.join("; ", contributors);
        conflicts.add(new ConflictDetail(
            targetClass.name, key.name(), key.desc(), contributors, "FAIL", message));
        throw new MixinConflictException(message, conflicts);
    }

    private static MethodNode generateAccessorOrInvoker(ClassNode targetClass,
                                                        ResolvedMethod accessorMethod,
                                                        List<ConflictDetail> conflicts) {
        AccessorSpec spec = AccessorSpec.from(accessorMethod.method());
        if (spec == null) {
            failAccessorConflict(targetClass, MethodKey.of(accessorMethod.method()),
                List.of(accessorMethod), conflicts);
        }
        String targetName = spec.targetName();
        if (targetName == null || targetName.isBlank()) {
            targetName = inferAccessorTarget(accessorMethod.method().name, spec.kind());
        }
        if (targetName == null || targetName.isBlank()) {
            failAccessorConflict(targetClass, MethodKey.of(accessorMethod.method()),
                List.of(accessorMethod), conflicts);
        }

        return switch (spec.kind()) {
            case FIELD_GETTER, FIELD_SETTER -> buildFieldAccessor(targetClass, accessorMethod.method(), targetName, spec.kind(), conflicts);
            case METHOD_INVOKER -> buildMethodInvoker(targetClass, accessorMethod.method(), targetName, conflicts);
        };
    }

    private static MethodNode buildFieldAccessor(ClassNode targetClass,
                                                 MethodNode accessor,
                                                 String targetName,
                                                 AccessorKind kind,
                                                 List<ConflictDetail> conflicts) {
        boolean isSetter = kind == AccessorKind.FIELD_SETTER;
        Type methodType = Type.getMethodType(accessor.desc);
        Type returnType = methodType.getReturnType();
        Type[] args = methodType.getArgumentTypes();

        if (isSetter) {
            if (returnType.getSort() != Type.VOID || args.length != 1) {
                failAccessorConflict(targetClass, MethodKey.of(accessor),
                    List.of(new ResolvedMethod("accessor", 0, ConflictOperation.ACCESSOR, accessor)), conflicts);
            }
        } else {
            if (returnType.getSort() == Type.VOID || args.length != 0) {
                failAccessorConflict(targetClass, MethodKey.of(accessor),
                    List.of(new ResolvedMethod("accessor", 0, ConflictOperation.ACCESSOR, accessor)), conflicts);
            }
        }

        String fieldDesc = isSetter ? args[0].getDescriptor() : returnType.getDescriptor();
        FieldNode field = findField(targetClass, targetName, fieldDesc);
        if (field == null) {
            failAccessorConflict(targetClass, MethodKey.of(accessor),
                List.of(new ResolvedMethod("accessor", 0, ConflictOperation.ACCESSOR, accessor)), conflicts);
        }

        MethodNode generated = new MethodNode(
            accessorAccess(accessor.access),
            accessor.name,
            accessor.desc,
            accessor.signature,
            accessor.exceptions == null ? null : accessor.exceptions.toArray(String[]::new)
        );
        generated.visibleAnnotations = null;
        generated.invisibleAnnotations = null;

        InsnList instructions = new InsnList();
        boolean fieldStatic = (field.access & Opcodes.ACC_STATIC) != 0;
        if (isSetter) {
            if (!fieldStatic) {
                instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            int valueIndex = (accessor.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            instructions.add(new VarInsnNode(args[0].getOpcode(Opcodes.ILOAD), valueIndex));
            instructions.add(new FieldInsnNode(fieldStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
                targetClass.name, field.name, field.desc));
            instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            if (!fieldStatic) {
                instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            instructions.add(new FieldInsnNode(fieldStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
                targetClass.name, field.name, field.desc));
            instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        }
        generated.instructions = instructions;
        generated.tryCatchBlocks = new ArrayList<>();
        return generated;
    }

    private static MethodNode buildMethodInvoker(ClassNode targetClass,
                                                 MethodNode invoker,
                                                 String targetName,
                                                 List<ConflictDetail> conflicts) {
        MethodNode target = findMethod(targetClass, targetName, invoker.desc);
        if (target == null) {
            failAccessorConflict(targetClass, MethodKey.of(invoker),
                List.of(new ResolvedMethod("invoker", 0, ConflictOperation.INVOKER, invoker)), conflicts);
        }

        boolean targetStatic = (target.access & Opcodes.ACC_STATIC) != 0;
        boolean invokerStatic = (invoker.access & Opcodes.ACC_STATIC) != 0;
        Type methodType = Type.getMethodType(invoker.desc);
        Type[] args = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();

        MethodNode generated = new MethodNode(
            accessorAccess(invoker.access),
            invoker.name,
            invoker.desc,
            invoker.signature,
            invoker.exceptions == null ? null : invoker.exceptions.toArray(String[]::new)
        );
        generated.visibleAnnotations = null;
        generated.invisibleAnnotations = null;

        InsnList instructions = new InsnList();
        int argIndex = 0;
        if (!targetStatic) {
            if (invokerStatic) {
                if (args.length == 0 || !args[0].getInternalName().equals(targetClass.name)) {
                    failAccessorConflict(targetClass, MethodKey.of(invoker),
                        List.of(new ResolvedMethod("invoker", 0, ConflictOperation.INVOKER, invoker)), conflicts);
                }
                instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                argIndex = 1;
            } else {
                instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
        }

        int localIndex = invokerStatic ? 0 : 1;
        for (int i = argIndex; i < args.length; i++) {
            instructions.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), localIndex));
            localIndex += args[i].getSize();
        }

        int opcode = targetStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
        instructions.add(new MethodInsnNode(opcode, targetClass.name, target.name, target.desc, false));
        instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

        generated.instructions = instructions;
        generated.tryCatchBlocks = new ArrayList<>();
        return generated;
    }

    private static FieldNode findField(ClassNode targetClass, String name, String desc) {
        for (FieldNode field : targetClass.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return field;
            }
        }
        return null;
    }

    private static int accessorAccess(int access) {
        return access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
    }

    private static String inferAccessorTarget(String methodName, AccessorKind kind) {
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        String[] prefixes = switch (kind) {
            case FIELD_GETTER, FIELD_SETTER -> new String[] {"get", "set", "is", "accessor"};
            case METHOD_INVOKER -> new String[] {"call", "invoke", "invoker"};
        };
        for (String prefix : prefixes) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                return decapitalize(methodName.substring(prefix.length()));
            }
        }
        return methodName;
    }

    private static String decapitalize(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.length() == 1) return value.toLowerCase();
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private enum AccessorKind {
        FIELD_GETTER,
        FIELD_SETTER,
        METHOD_INVOKER
    }

    private record AccessorSpec(AccessorKind kind, String targetName) {
        static AccessorSpec from(MethodNode method) {
            if (method == null) return null;
            AccessorSpec spec = findAccessorSpec(method.visibleAnnotations, method);
            if (spec != null) return spec;
            return findAccessorSpec(method.invisibleAnnotations, method);
        }

        private static AccessorSpec findAccessorSpec(List<AnnotationNode> annotations, MethodNode method) {
            if (annotations == null) return null;
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc != null && annotation.desc.endsWith("/Accessor;")) {
                    String name = extractAnnotationValue(annotation, "value");
                    AccessorKind kind = isSetterSignature(method.desc) ? AccessorKind.FIELD_SETTER : AccessorKind.FIELD_GETTER;
                    return new AccessorSpec(kind, name);
                }
                if (annotation.desc != null && annotation.desc.endsWith("/Invoker;")) {
                    String name = extractAnnotationValue(annotation, "value");
                    return new AccessorSpec(AccessorKind.METHOD_INVOKER, name);
                }
            }
            return null;
        }
    }

    private static String extractAnnotationValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            Object name = annotation.values.get(i);
            Object value = annotation.values.get(i + 1);
            if (key.equals(name) && value instanceof String stringValue) {
                return stringValue;
            }
        }
        return null;
    }

    private static boolean isSetterSignature(String desc) {
        Type type = Type.getMethodType(desc);
        return type.getReturnType().getSort() == Type.VOID && type.getArgumentTypes().length == 1;
    }

    public record ResolutionReport(
        int              modifiedMethods,
        int              addedMethods,
        int              bridgeMethods,
        int              directReplacements,
        int              semanticMerges,
        int              deduplicatedMethods,
        List<ConflictDetail> conflicts
    ) {
        public boolean hasModifications() {
            return modifiedMethods > 0 || addedMethods > 0;
        }

        /** Returns {@code true} if any conflict resulted in a BRIDGE or OVERWRITE. */
        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        public String cacheSummary() {
            return "modified=" + modifiedMethods
                + ",added=" + addedMethods
                + ",bridged=" + bridgeMethods
                + ",replaced=" + directReplacements
                + ",merged=" + semanticMerges
                + ",deduped=" + deduplicatedMethods
                + ",conflicts=" + conflicts.size();
        }
    }
}
