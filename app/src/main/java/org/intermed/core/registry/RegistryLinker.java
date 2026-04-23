package org.intermed.core.registry;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * invokedynamic bootstrap methods for registry virtualisation
 * (ТЗ 3.2.2, Requirement 3 — MethodHandle + invokedynamic + ConstantCallSite).
 *
 * <h3>Hot-path design</h3>
 * Every registry GET call site is compiled by {@link RegistryHookTransformer} to
 * an {@code INVOKEDYNAMIC} instruction.  This bootstrap method returns a
 * {@link ConstantCallSite} whose target is a {@link SwitchPoint}-guarded pair:
 * <pre>
 *   ConstantCallSite → sp.guardWithTest(slowMH, frozenMH)
 *        ↑ never changes            ↑ flips once at freeze()
 * </pre>
 * <ul>
 *   <li><b>Before {@link #freeze()}</b> — {@code slowMH} is active: delegates
 *       to {@link VirtualRegistryService} which tolerates mutable registry
 *       state.</li>
 *   <li><b>After {@link #freeze()}</b> — {@link SwitchPoint#invalidateAll}
 *       atomically deoptimises every JIT-compiled frame containing these sites.
 *       On recompilation the guard test is a known-{@code false} constant,
 *       so C2 eliminates the dead branch and inlines {@code frozenMH} directly
 *       into the caller — a direct MethodHandle chain to the MPHF flat-array
 *       tables in {@link VirtualRegistry}.  No {@code Map} lookups, no
 *       volatile reads on a {@link java.lang.invoke.MutableCallSite}, no guards
 *       in the steady-state JIT code.</li>
 * </ul>
 *
 * <p>REGISTER call sites use a plain {@link ConstantCallSite} immediately
 * (registry mutation is always intercepted the same way).
 *
 * <h3>Monomorphic inline cache (frozen fast path)</h3>
 * Each GET site carries a {@link GetSiteBinding} with a single-entry cache
 * {@code (scope, key) → rawId}.  On a frozen cache hit, the call skips
 * the MPHF lookup entirely and goes straight to the raw-id flat array.
 * Cache misses increment {@link #frozenDynamicLookupCount()} for observability.
 */
public final class RegistryLinker {

    /**
     * Live SwitchPoints — one per bootstrapped GET site.
     * Cleared (and invalidated) by {@link #freeze()}.
     */
    private static final CopyOnWriteArrayList<SwitchPoint> SWITCH_POINTS = new CopyOnWriteArrayList<>();

    /**
     * Number of frozen-path MPHF lookups that missed the per-site monomorphic
     * cache.  Reset to zero by {@link #freeze()}.
     */
    private static final AtomicInteger FROZEN_DYNAMIC_LOOKUPS = new AtomicInteger();

    // ── Cached MethodHandle constants ─────────────────────────────────────────

    private static final MethodHandle INTERCEPT_REGISTER_HANDLE;
    private static final MethodHandle SLOW_PATH_OBJECT_GET_1_HANDLE;
    private static final MethodHandle SLOW_PATH_OBJECT_GET_2_HANDLE;
    private static final MethodHandle SLOW_PATH_OBJECT_GET_HANDLE;
    private static final MethodHandle SLOW_PATH_OPTIONAL_GET_1_HANDLE;
    private static final MethodHandle SLOW_PATH_OPTIONAL_GET_2_HANDLE;
    private static final MethodHandle SLOW_PATH_OPTIONAL_GET_HANDLE;
    private static final MethodHandle SLOW_PATH_RAW_ID_GET_1_HANDLE;
    private static final MethodHandle SLOW_PATH_RAW_ID_GET_2_HANDLE;
    private static final MethodHandle SLOW_PATH_RAW_ID_HANDLE;
    private static final MethodHandle FROZEN_OBJECT_GET_1_HANDLE;
    private static final MethodHandle FROZEN_OBJECT_GET_2_HANDLE;
    private static final MethodHandle FROZEN_OBJECT_GET_HANDLE;
    private static final MethodHandle FROZEN_OPTIONAL_GET_1_HANDLE;
    private static final MethodHandle FROZEN_OPTIONAL_GET_2_HANDLE;
    private static final MethodHandle FROZEN_OPTIONAL_GET_HANDLE;
    private static final MethodHandle FAST_RAW_ID_1_HANDLE;
    private static final MethodHandle FAST_RAW_ID_2_HANDLE;
    private static final MethodHandle FAST_RAW_ID_HANDLE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            INTERCEPT_REGISTER_HANDLE = lookup.findStatic(RegistryLinker.class,
                "interceptRegister",
                MethodType.methodType(Object.class, String.class, Object.class, Object.class, Object.class));
            SLOW_PATH_OBJECT_GET_1_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathObjectGet1",
                MethodType.methodType(Object.class, GetSiteBinding.class, Object.class));
            SLOW_PATH_OBJECT_GET_2_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathObjectGet2",
                MethodType.methodType(Object.class, GetSiteBinding.class, Object.class, Object.class));
            SLOW_PATH_OBJECT_GET_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathObjectGet",
                MethodType.methodType(Object.class, GetSiteBinding.class, Object[].class));
            SLOW_PATH_OPTIONAL_GET_1_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathOptionalGet1",
                MethodType.methodType(Optional.class, GetSiteBinding.class, Object.class));
            SLOW_PATH_OPTIONAL_GET_2_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathOptionalGet2",
                MethodType.methodType(Optional.class, GetSiteBinding.class, Object.class, Object.class));
            SLOW_PATH_OPTIONAL_GET_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathOptionalGet",
                MethodType.methodType(Optional.class, GetSiteBinding.class, Object[].class));
            SLOW_PATH_RAW_ID_GET_1_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathRawIdGet1",
                MethodType.methodType(int.class, GetSiteBinding.class, Object.class));
            SLOW_PATH_RAW_ID_GET_2_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathRawIdGet2",
                MethodType.methodType(int.class, GetSiteBinding.class, Object.class, Object.class));
            SLOW_PATH_RAW_ID_HANDLE = lookup.findStatic(RegistryLinker.class,
                "slowPathRawIdGet",
                MethodType.methodType(int.class, GetSiteBinding.class, Object[].class));
            FROZEN_OBJECT_GET_1_HANDLE = lookup.findStatic(RegistryLinker.class,
                "frozenObjectGet1",
                MethodType.methodType(Object.class, GetSiteBinding.class, Object.class));
            FROZEN_OBJECT_GET_2_HANDLE = lookup.findStatic(RegistryLinker.class,
                "frozenObjectGet2",
                MethodType.methodType(Object.class, GetSiteBinding.class, Object.class, Object.class));
            FROZEN_OBJECT_GET_HANDLE = lookup.findStatic(RegistryLinker.class,
                "frozenObjectGet",
                MethodType.methodType(Object.class, GetSiteBinding.class, Object[].class));
            FROZEN_OPTIONAL_GET_1_HANDLE = lookup.findStatic(RegistryLinker.class,
                "frozenOptionalGet1",
                MethodType.methodType(Optional.class, GetSiteBinding.class, Object.class));
            FROZEN_OPTIONAL_GET_2_HANDLE = lookup.findStatic(RegistryLinker.class,
                "frozenOptionalGet2",
                MethodType.methodType(Optional.class, GetSiteBinding.class, Object.class, Object.class));
            FROZEN_OPTIONAL_GET_HANDLE = lookup.findStatic(RegistryLinker.class,
                "frozenOptionalGet",
                MethodType.methodType(Optional.class, GetSiteBinding.class, Object[].class));
            FAST_RAW_ID_1_HANDLE = lookup.findStatic(RegistryLinker.class,
                "fastRawIdGet1",
                MethodType.methodType(int.class, Object.class));
            FAST_RAW_ID_2_HANDLE = lookup.findStatic(RegistryLinker.class,
                "fastRawIdGet2",
                MethodType.methodType(int.class, Object.class, Object.class));
            FAST_RAW_ID_HANDLE = lookup.findStatic(RegistryLinker.class,
                "fastRawIdGet",
                MethodType.methodType(int.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RegistryLinker() {}

    // ── Bootstrap methods ─────────────────────────────────────────────────────

    /**
     * Bootstrap for {@code INVOKEDYNAMIC register} instructions.
     *
     * <p>Returns a {@link ConstantCallSite}: registration semantics never change,
     * so there is no need for a SwitchPoint.  The handle mirrors every object
     * into {@link VirtualRegistry} and returns the original value unchanged.
     */
    public static CallSite bootstrapRegister(MethodHandles.Lookup lookup,
                                             String name,
                                             MethodType type) {
        MethodHandle base = MethodHandles.insertArguments(
            INTERCEPT_REGISTER_HANDLE, 0, resolveLookupModId(lookup));
        return new ConstantCallSite(adaptRegisterHandle(base, type));
    }

    /**
     * Bootstrap for {@code INVOKEDYNAMIC registryGet} instructions.
     *
     * <p>Returns a {@link ConstantCallSite} wrapping a {@link SwitchPoint}-guarded
     * pair {@code sp.guardWithTest(slowMH, frozenMH)}.  The ConstantCallSite target
     * is immutable, giving the JIT the same inlining guarantee as a {@code ldc}
     * constant.  The SwitchPoint flips exactly once when {@link #freeze()} is
     * called, after which C2 constant-folds the guard to {@code false} and inlines
     * {@code frozenMH} — the direct MPHF flat-array path — with zero overhead.
     */
    public static CallSite bootstrapGet(MethodHandles.Lookup lookup,
                                        String name,
                                        MethodType type) {
        GetSiteBinding binding = new GetSiteBinding(
            ReturnKind.from(type.returnType()),
            resolveLookupModId(lookup)
        );

        SwitchPoint sp = new SwitchPoint();
        SWITCH_POINTS.add(sp);

        MethodHandle slowMH   = slowTarget(binding, type);
        MethodHandle frozenMH = frozenTarget(binding, type);

        // ConstantCallSite: the JIT treats the target MH as a compile-time constant.
        // SwitchPoint.guardWithTest routes to slowMH until invalidation, then to
        // frozenMH.  After SwitchPoint.invalidateAll(), the guard condition is a
        // JIT-visible constant false → dead-code elimination of slowMH path.
        return new ConstantCallSite(sp.guardWithTest(slowMH, frozenMH));
    }

    /** Alias — legacy name used by some bridge call sites. */
    public static CallSite bootstrap(MethodHandles.Lookup lookup,
                                     String name,
                                     MethodType type) {
        return bootstrapGet(lookup, name, type);
    }

    // ── Register interception ─────────────────────────────────────────────────

    public static Object interceptRegister(String modId, Object registry, Object key, Object value) {
        if (key != null && value != null) {
            VirtualRegistryService.registerVirtualized(
                modId,
                RegistryIdentity.scopeOf(registry),
                String.valueOf(key),
                -1,
                value
            );
        }
        return value;
    }

    // ── Slow-path dispatch (pre-freeze, and fallback when SwitchPoint not yet
    //    invalidated but VirtualRegistry is already frozen) ───────────────────

    /**
     * If the registry is already frozen (e.g. {@link VirtualRegistry#freeze()} was
     * called before {@link #freeze()}), delegates directly to the frozen fast path
     * so the per-site monomorphic cache is populated correctly.  The SwitchPoint
     * invalidation in {@link #freeze()} will eliminate this branch in JIT code.
     */
    public static Object slowPathObjectGet1(GetSiteBinding binding, Object key) {
        if (VirtualRegistry.isFrozen()) {
            return frozenObjectGet1(binding, key);
        }
        return VirtualRegistryService.lookupValue(binding.modId, key);
    }

    public static Object slowPathObjectGet2(GetSiteBinding binding, Object registry, Object key) {
        if (VirtualRegistry.isFrozen()) {
            return frozenObjectGet2(binding, registry, key);
        }
        return VirtualRegistryService.lookupValue(binding.modId, registry, key);
    }

    public static Object slowPathObjectGet(GetSiteBinding binding, Object[] args) {
        if (VirtualRegistry.isFrozen()) {
            return frozenObjectGet(binding, args);
        }
        return unfrozenObjectGet(binding.modId, args);
    }

    public static Optional<?> slowPathOptionalGet1(GetSiteBinding binding, Object key) {
        Object value = slowPathObjectGet1(binding, key);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    public static Optional<?> slowPathOptionalGet2(GetSiteBinding binding, Object registry, Object key) {
        Object value = slowPathObjectGet2(binding, registry, key);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    public static Optional<?> slowPathOptionalGet(GetSiteBinding binding, Object[] args) {
        if (VirtualRegistry.isFrozen()) {
            return frozenOptionalGet(binding, args);
        }
        return unfrozenOptionalGet(binding.modId, args);
    }

    // Raw-id lookups delegate to the fast path unconditionally: getRawIdFast()
    // already handles both unfrozen (ConcurrentHashMap) and frozen (flat array).
    public static int slowPathRawIdGet1(GetSiteBinding binding, Object value) {
        return fastRawIdGet1(value);
    }

    public static int slowPathRawIdGet2(GetSiteBinding binding, Object registry, Object value) {
        return fastRawIdGet2(registry, value);
    }

    public static int slowPathRawIdGet(GetSiteBinding binding, Object[] args) {
        return fastRawIdGet(args);
    }

    // ── Freeze ────────────────────────────────────────────────────────────────

    /**
     * Atomically invalidates all outstanding GET call-site {@link SwitchPoint}s.
     *
     * <p>After this call:
     * <ol>
     *   <li>Every JIT-compiled frame containing an {@code INVOKEDYNAMIC registryGet}
     *       site is deoptimised.</li>
     *   <li>On recompilation the SwitchPoint guard is a known-{@code false}
     *       constant, eliminating the slow-path branch entirely.</li>
     *   <li>The frozen MPHF flat-array path is inlined directly by C2 with no
     *       remaining overhead — full ТЗ 3.2.2 compliance.</li>
     * </ol>
     */
    public static void freeze() {
        int count = SWITCH_POINTS.size();
        int rewrittenGets = RegistryHookTransformer.rewrittenGetSiteCount();
        if (count > 0) {
            SwitchPoint[] sps = SWITCH_POINTS.toArray(SwitchPoint[]::new);
            SWITCH_POINTS.clear();
            SwitchPoint.invalidateAll(sps);
        }
        FROZEN_DYNAMIC_LOOKUPS.set(0);
        String detail;
        if (count == 0 && rewrittenGets > 0) {
            detail = "rewritten=" + rewrittenGets
                + ", bootstrapped=0; transformed GET call sites were present, but none executed before freeze";
        } else if (count == 0) {
            detail = "rewritten=0; no eligible registry GET call sites were rewritten before freeze";
        } else {
            detail = "rewritten=" + rewrittenGets + ", bootstrapped=" + count;
        }
        System.out.printf(
            "[RegistryLinker] freeze(): invalidated %d INVOKEDYNAMIC GET site(s)"
            + " → MPHF flat-array fast path active (ТЗ 3.2.2, ConstantCallSite + SwitchPoint) [%s].%n",
            count,
            detail);
    }

    // ── Test support ──────────────────────────────────────────────────────────

    static void resetForTests() {
        SWITCH_POINTS.clear();
        FROZEN_DYNAMIC_LOOKUPS.set(0);
        RegistryHookTransformer.resetForTests();
    }

    /** Returns the number of post-freeze MPHF lookups that missed the per-site cache. */
    static int frozenDynamicLookupCount() {
        return FROZEN_DYNAMIC_LOOKUPS.get();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static Object unfrozenObjectGet(String modId, Object[] args) {
        return VirtualRegistryService.lookupValue(
            modId, extractRegistryCandidate(args), extractLookupKey(args));
    }

    private static Optional<?> unfrozenOptionalGet(String modId, Object[] args) {
        Object value = unfrozenObjectGet(modId, args);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    // ── Frozen-path methods (MPHF + monomorphic cache) ────────────────────────

    private static Object frozenObjectGet1(GetSiteBinding binding, Object key) {
        int rawId = resolveFrozenRawId(binding, null, key);
        return rawId >= 0 ? VirtualRegistry.getFastByRawId(rawId) : null;
    }

    private static Object frozenObjectGet2(GetSiteBinding binding, Object registry, Object key) {
        int rawId = resolveFrozenRawId(binding, registry, key);
        return rawId >= 0 ? VirtualRegistry.getFastByRawId(rawId) : null;
    }

    private static Object frozenObjectGet(GetSiteBinding binding, Object[] args) {
        int rawId = resolveFrozenRawId(binding, args);
        return rawId >= 0 ? VirtualRegistry.getFastByRawId(rawId) : null;
    }

    private static Optional<?> frozenOptionalGet1(GetSiteBinding binding, Object key) {
        Object value = frozenObjectGet1(binding, key);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    private static Optional<?> frozenOptionalGet2(GetSiteBinding binding, Object registry, Object key) {
        Object value = frozenObjectGet2(binding, registry, key);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    private static Optional<?> frozenOptionalGet(GetSiteBinding binding, Object[] args) {
        Object value = frozenObjectGet(binding, args);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    private static int fastRawIdGet1(Object value) {
        return VirtualRegistry.getRawIdFast(value);
    }

    private static int fastRawIdGet2(Object registry, Object value) {
        return VirtualRegistry.getRawIdFast(value);
    }

    private static int fastRawIdGet(Object[] args) {
        return VirtualRegistry.getRawIdFast(extractLookupKey(args));
    }

    // ── Monomorphic cache resolve (tracks MPHF miss count for observability) ──

    private static int resolveFrozenRawId(GetSiteBinding binding, Object[] args) {
        return resolveFrozenRawId(
            binding,
            RegistryIdentity.scopeOf(extractRegistryCandidate(args)),
            extractLookupKey(args));
    }

    private static int resolveFrozenRawId(GetSiteBinding binding,
                                           Object registryCandidate,
                                           Object lookupKey) {
        return resolveFrozenRawId(
            binding,
            RegistryIdentity.scopeOf(registryCandidate),
            lookupKey);
    }

    private static int resolveFrozenRawId(GetSiteBinding binding,
                                           String scope,
                                           Object lookupKey) {
        if (lookupKey == null) return -1;
        String key = String.valueOf(lookupKey);

        // Fast: monomorphic cache hit — no MPHF query
        if (binding.matches(scope, key)) {
            return binding.cachedRawId;
        }

        // Slow: MPHF lookup (O(1), ByteBuddy-compiled, flat array)
        FROZEN_DYNAMIC_LOOKUPS.incrementAndGet();
        int rawId = VirtualRegistryService.lookupGlobalId(binding.modId, scope, key);
        if (rawId >= 0) {
            binding.cache(scope, key, rawId);
        } else {
            binding.clearCache();
        }
        return rawId;
    }

    // ── MethodHandle target builders ──────────────────────────────────────────

    private static MethodHandle adaptRegisterHandle(MethodHandle base, MethodType type) {
        int baseParams  = base.type().parameterCount();
        int targetParams = type.parameterCount();
        MethodHandle handle = base;
        if (targetParams > baseParams) {
            handle = MethodHandles.dropArguments(
                handle, baseParams,
                type.parameterList().subList(baseParams, targetParams));
        }
        return handle.asType(type);
    }

    private static MethodHandle slowTarget(GetSiteBinding binding, MethodType type) {
        MethodHandle direct = switch (type.parameterCount()) {
            case 1 -> switch (binding.returnKind) {
                case OBJECT   -> SLOW_PATH_OBJECT_GET_1_HANDLE.bindTo(binding);
                case OPTIONAL -> SLOW_PATH_OPTIONAL_GET_1_HANDLE.bindTo(binding);
                case RAW_ID   -> SLOW_PATH_RAW_ID_GET_1_HANDLE.bindTo(binding);
            };
            case 2 -> switch (binding.returnKind) {
                case OBJECT   -> SLOW_PATH_OBJECT_GET_2_HANDLE.bindTo(binding);
                case OPTIONAL -> SLOW_PATH_OPTIONAL_GET_2_HANDLE.bindTo(binding);
                case RAW_ID   -> SLOW_PATH_RAW_ID_GET_2_HANDLE.bindTo(binding);
            };
            default -> switch (binding.returnKind) {
                case OBJECT   -> adaptArrayHandle(SLOW_PATH_OBJECT_GET_HANDLE.bindTo(binding), type);
                case OPTIONAL -> adaptArrayHandle(SLOW_PATH_OPTIONAL_GET_HANDLE.bindTo(binding), type);
                case RAW_ID   -> adaptArrayHandle(SLOW_PATH_RAW_ID_HANDLE.bindTo(binding), type);
            };
        };
        return type.parameterCount() <= 2 ? direct.asType(type) : direct;
    }

    private static MethodHandle frozenTarget(GetSiteBinding binding, MethodType type) {
        MethodHandle direct = switch (type.parameterCount()) {
            case 1 -> switch (binding.returnKind) {
                case OBJECT   -> FROZEN_OBJECT_GET_1_HANDLE.bindTo(binding);
                case OPTIONAL -> FROZEN_OPTIONAL_GET_1_HANDLE.bindTo(binding);
                case RAW_ID   -> FAST_RAW_ID_1_HANDLE;
            };
            case 2 -> switch (binding.returnKind) {
                case OBJECT   -> FROZEN_OBJECT_GET_2_HANDLE.bindTo(binding);
                case OPTIONAL -> FROZEN_OPTIONAL_GET_2_HANDLE.bindTo(binding);
                case RAW_ID   -> FAST_RAW_ID_2_HANDLE;
            };
            default -> switch (binding.returnKind) {
                case OBJECT   -> adaptArrayHandle(FROZEN_OBJECT_GET_HANDLE.bindTo(binding), type);
                case OPTIONAL -> adaptArrayHandle(FROZEN_OPTIONAL_GET_HANDLE.bindTo(binding), type);
                case RAW_ID   -> adaptArrayHandle(FAST_RAW_ID_HANDLE, type);
            };
        };
        return type.parameterCount() <= 2 ? direct.asType(type) : direct;
    }

    private static MethodHandle adaptArrayHandle(MethodHandle handle, MethodType type) {
        return handle.asCollector(Object[].class, type.parameterCount()).asType(type);
    }

    // ── Argument extraction helpers ───────────────────────────────────────────

    private static Object extractLookupKey(Object[] args) {
        return (args == null || args.length == 0) ? null : args[args.length - 1];
    }

    private static Object extractRegistryCandidate(Object[] args) {
        return (args == null || args.length < 2) ? null : args[0];
    }

    private static String resolveLookupModId(MethodHandles.Lookup lookup) {
        if (lookup == null) return "";
        Class<?> cls = lookup.lookupClass();
        if (cls == null) return "";
        ClassLoader cl = cls.getClassLoader();
        return extractNodeId(cl);
    }

    private static String extractNodeId(ClassLoader loader) {
        if (loader == null) {
            return "";
        }
        try {
            java.lang.reflect.Method method = loader.getClass().getMethod("getNodeId");
            if (method.getReturnType() != String.class || method.getParameterCount() != 0) {
                return "";
            }
            Object value = method.invoke(loader);
            return value instanceof String id ? id : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Per-call-site state: return type and single-entry monomorphic inline cache.
     *
     * <p>The cache stores the most recently observed {@code (scope, key) → rawId}
     * triple.  On a hit, the frozen path bypasses the MPHF entirely and goes
     * straight to the raw-id flat array in {@link VirtualRegistry}.  Volatile
     * fields ensure safe publication across threads; the worst case of a
     * concurrent invalidation is one redundant MPHF query.
     */
    static final class GetSiteBinding {
        final ReturnKind returnKind;
        final String modId;
        volatile String cachedScope  = "";
        volatile String cachedKey;
        volatile int    cachedRawId  = -1;

        GetSiteBinding(ReturnKind returnKind, String modId) {
            this.returnKind = returnKind;
            this.modId = modId;
        }

        boolean matches(String scope, String key) {
            return cachedRawId >= 0
                && java.util.Objects.equals(cachedScope, scope == null ? "" : scope)
                && java.util.Objects.equals(cachedKey, key);
        }

        void cache(String scope, String key, int rawId) {
            this.cachedScope = scope == null ? "" : scope;
            this.cachedKey   = key;
            this.cachedRawId = rawId;
        }

        void clearCache() {
            this.cachedScope  = "";
            this.cachedKey    = null;
            this.cachedRawId  = -1;
        }
    }

    private enum ReturnKind {
        OBJECT, OPTIONAL, RAW_ID;

        static ReturnKind from(Class<?> returnType) {
            if (returnType == int.class || returnType == Integer.class) return RAW_ID;
            if (Optional.class.isAssignableFrom(returnType)) return OPTIONAL;
            return OBJECT;
        }
    }
}
