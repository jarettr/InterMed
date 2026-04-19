package org.intermed.core.mixin;

import org.intermed.core.ast.ResolutionEngine;
import org.intermed.core.ast.ResolutionEngine.ConflictDetail;
import org.intermed.core.ast.ResolutionEngine.MixinContribution;
import org.intermed.core.ast.ResolutionEngine.MixinConflictException;
import org.intermed.core.ast.ResolutionEngine.ResolutionReport;
import org.intermed.core.ast.SemanticContractRegistry;
import org.intermed.core.ast.AstMetadataReclaimer;
import org.intermed.core.cache.AOTCacheManager;
import org.intermed.core.classloading.DagAwareClassWriter;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.registry.RegistryLinker;
import org.intermed.core.remapping.InterMedRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolution Engine (ТЗ 3.2.3, Требование 5-6).
 * Builds AST tree of a class to detect and resolve Mixin conflicts.
 * Results are AOT-cached on disk to speed up subsequent launches.
 */
public final class MixinASTAnalyzer {

    private MixinASTAnalyzer() {}

    public static byte[] analyzeAndResolve(String className, byte[] originalBytes, List<MixinInfo> mixinInfos) {
        ClassNode targetNode = null;
        List<MixinContribution> contributions = new ArrayList<>();
        try {
            ClassReader targetReader = new ClassReader(originalBytes);
            targetNode = new ClassNode();
            targetReader.accept(targetNode, 0);

            List<String> cacheInputs = new ArrayList<>();

            for (MixinInfo info : sortedMixins(mixinInfos)) {
                byte[] mixinBytes = LifecycleManager.getClassBytesFromDAG(info.getMixinClass());
                if (mixinBytes == null) {
                    System.err.println("[AST-Engine] Missing mixin bytecode in DAG: " + info.getMixinClass());
                    continue;
                }

                ClassReader mixinReader = new ClassReader(mixinBytes);
                ClassNode mixinNode = new ClassNode();
                mixinReader.accept(mixinNode, 0);

                contributions.add(new MixinContribution(
                    info.getMixinClass(),
                    info.getPriority(),
                    info.getRegistrationOrder(),
                    mixinNode
                ));
                // Include modVersion in the cache key: if the mod updates, all its mixins
                // will have a different cache fingerprint and be re-resolved automatically.
                cacheInputs.add(info.getMixinClass() + "@v" + info.getModVersion()
                    + "@p" + info.getPriority()
                    + "@o" + info.getRegistrationOrder() + ":"
                    + AOTCacheManager.sha256(mixinBytes));
            }

            if (contributions.isEmpty()) {
                return originalBytes;
            }

            String inputHash = buildInputHash(className, originalBytes, cacheInputs);
            AOTCacheManager.CachedClass cached = AOTCacheManager.getCachedEntry(className, inputHash);
            if (cached != null) {
                String cacheKind = cached.metadata().extraMetadata().getOrDefault("cache.kind", "TRANSFORMED");
                System.out.println("\033[1;32m[AOT] Cache HIT (" + cacheKind + "): " + className + "\033[0m");
                return cached.bytecode();
            }

            ResolutionReport report;
            try {
                report = ResolutionEngine.resolveMixinConflictsWithMetadata(targetNode, contributions);
            } catch (MixinConflictException conflict) {
                // Emit the full user-facing conflict report to stderr and re-throw.
                System.err.println("\033[1;31m[AST-Engine] " + conflict.conflictReport() + "\033[0m");
                throw conflict;
            }

            if (report.hasConflicts()) {
                // Log informational detail for BRIDGE / OVERWRITE resolutions.
                System.out.println("[AST-Engine] Conflict resolutions for " + className + ":");
                for (ConflictDetail detail : report.conflicts()) {
                    System.out.println("  " + detail.summary());
                }
            }

            Map<String, String> cacheMetadata = buildCacheMetadata(report, cacheInputs, originalBytes);

            if (!report.hasModifications()) {
                AOTCacheManager.saveToCache(className, inputHash, originalBytes, cacheMetadata);
                System.out.println("[AOT] Cached pass-through class: " + className
                    + " [deduped=" + report.deduplicatedMethods()
                    + ", conflicts=" + report.conflicts().size() + "]");
                return originalBytes;
            }

            ClassWriter writer = DagAwareClassWriter.create(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            targetNode.accept(writer);
            byte[] result = writer.toByteArray();
            AOTCacheManager.saveToCache(className, inputHash, result, cacheMetadata);

            System.out.println("[AOT] Cached transformed class: " + className
                + " [modified=" + report.modifiedMethods()
                + ", merged=" + report.semanticMerges()
                + ", bridged=" + report.bridgeMethods()
                + ", replaced=" + report.directReplacements()
                + ", deduped=" + report.deduplicatedMethods()
                + ", conflicts=" + report.conflicts().size() + "]");
            return result;
        } catch (MixinConflictException e) {
            throw e; // already logged above — propagate to caller
        } catch (Exception e) {
            System.err.println("[AST-Engine] Failed to analyze " + className + ": " + e.getMessage());
            e.printStackTrace();
            throw new IllegalStateException("Failed to analyze mixins for " + className, e);
        } finally {
            reclaimAstMetadata(className, targetNode, contributions);
        }
    }

    private static void reclaimAstMetadata(String className,
                                           ClassNode targetNode,
                                           List<MixinContribution> contributions) {
        if (!RuntimeConfig.get().isMixinAstReclaimEnabled()) {
            return;
        }
        AstMetadataReclaimer.ReclaimStats stats = AstMetadataReclaimer.reclaim(targetNode, contributions);
        if (stats.hasReclaimedContent()) {
            System.out.println("[AST-Engine] Reclaimed AST metadata for " + className
                + " [classes=" + stats.classCount()
                + ", methods=" + stats.methodCount()
                + ", fields=" + stats.fieldCount()
                + ", instructions=" + stats.instructionCount() + "]");
        }
    }

    private static List<MixinInfo> sortedMixins(List<MixinInfo> mixinInfos) {
        // Index the input list so stable ordering by insertion position is explicit
        // when two mixins share the same priority AND have the default registrationOrder
        // (Integer.MAX_VALUE, set by the 4-arg MixinInfo constructor).  Without this,
        // the sort is correct (Java sort is stable) but the intent is opaque and fragile.
        List<MixinInfo> indexed = new java.util.ArrayList<>(mixinInfos);
        return indexed.stream()
            .sorted(Comparator
                .comparingInt(MixinInfo::getPriority)
                .reversed()
                .thenComparingInt((MixinInfo m) -> {
                    int order = m.getRegistrationOrder();
                    return order == Integer.MAX_VALUE ? indexed.indexOf(m) : order;
                }))
            .collect(Collectors.toList());
    }

    private static String buildInputHash(String className, byte[] originalBytes, List<String> mixinInputs) {
        String descriptor = "format=3"
            + "|class=" + className
            + "|policy=" + RuntimeConfig.get().getMixinConflictPolicy()
            + "|runtimeConfig=" + RuntimeConfig.get().cacheFingerprint()
            + "|mappingFingerprint=" + LifecycleManager.DICTIONARY.fingerprint()
            + "|runtimeFingerprint=" + AOTCacheManager.runtimeComponentFingerprint(
                MixinASTAnalyzer.class,
                ResolutionEngine.class,
                SemanticContractRegistry.class,
                InterMedRemapper.class,
                RegistryLinker.class,
                InterMedPlatformAgent.class,
                MixinTransformer.class
            )
            + "|target=" + AOTCacheManager.sha256(originalBytes)
            + "|mixins=" + String.join("|", mixinInputs);
        return AOTCacheManager.sha256(descriptor);
    }

    private static Map<String, String> buildCacheMetadata(ResolutionReport report,
                                                          List<String> mixinInputs,
                                                          byte[] originalBytes) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("cache.kind", report.hasModifications() ? "TRANSFORMED" : "PASS_THROUGH");
        metadata.put("resolution.policy", RuntimeConfig.get().getMixinConflictPolicy());
        metadata.put("resolution.modifiedMethods", Integer.toString(report.modifiedMethods()));
        metadata.put("resolution.addedMethods", Integer.toString(report.addedMethods()));
        metadata.put("resolution.bridgeMethods", Integer.toString(report.bridgeMethods()));
        metadata.put("resolution.directReplacements", Integer.toString(report.directReplacements()));
        metadata.put("resolution.semanticMerges", Integer.toString(report.semanticMerges()));
        metadata.put("resolution.deduplicatedMethods", Integer.toString(report.deduplicatedMethods()));
        metadata.put("resolution.conflicts", Integer.toString(report.conflicts().size()));
        metadata.put("resolution.summary", report.cacheSummary());
        metadata.put("mixins.count", Integer.toString(mixinInputs.size()));
        metadata.put("mixins.hash", AOTCacheManager.sha256(String.join("|", mixinInputs)));
        metadata.put("target.hash", AOTCacheManager.sha256(originalBytes));
        return metadata;
    }
}
