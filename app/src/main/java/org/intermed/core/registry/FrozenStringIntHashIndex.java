package org.intermed.core.registry;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Frozen collision-free string -> int index for hot-path registry lookups.
 *
 * <p>The builder computes a CHD-style minimal perfect hash:
 * keys are bucketed by a primary hash, then each non-trivial bucket searches
 * for a displacement seed that maps all keys to unique slots in the final
 * flat array. The slot table size is always exactly equal to the number of
 * entries, so the frozen structure is minimal-perfect rather than merely
 * collision-free.
 *
 * <p>At runtime the lookup function is served by a ByteBuddy-generated class.
 * The frozen path therefore uses only flat arrays plus bytecode-compiled hash
 * logic; no {@link java.util.Map} lookups are required after freeze.
 */
final class FrozenStringIntHashIndex {

    private static final int MAX_GLOBAL_SEED = 4_096;
    private static final int MAX_BUCKET_SEED = 1 << 20;
    private static final String GENERATED_LOOKUP_NAME = "org.intermed.core.registry.CompiledFrozenStringIntLookup";
    private static volatile Constructor<? extends GeneratedFrozenStringIntLookupBase> generatedLookupConstructor;

    private static final FrozenStringIntHashIndex EMPTY =
        new FrozenStringIntHashIndex(
            0,
            0,
            0,
            new int[0],
            new String[0],
            new int[0],
            EmptyLookup.INSTANCE
        );

    private final int size;
    private final int slotCount;
    private final int globalSeed;
    private final int[] bucketSeeds;
    private final String[] slotKeys;
    private final int[] slotValues;
    private final FrozenStringIntLookup lookup;

    private FrozenStringIntHashIndex(int size,
                                     int slotCount,
                                     int globalSeed,
                                     int[] bucketSeeds,
                                     String[] slotKeys,
                                     int[] slotValues,
                                     FrozenStringIntLookup lookup) {
        this.size = size;
        this.slotCount = slotCount;
        this.globalSeed = globalSeed;
        this.bucketSeeds = bucketSeeds;
        this.slotKeys = slotKeys;
        this.slotValues = slotValues;
        this.lookup = lookup;
    }

    static FrozenStringIntHashIndex empty() {
        return EMPTY;
    }

    static FrozenStringIntHashIndex build(Map<String, Integer> entries) {
        if (entries == null || entries.isEmpty()) {
            return empty();
        }

        List<Entry> normalized = new ArrayList<>(entries.size());
        entries.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                normalized.add(new Entry(key, value));
            }
        });
        if (normalized.isEmpty()) {
            return empty();
        }

        BuildPlan plan = buildPlan(normalized);
        return new FrozenStringIntHashIndex(
            normalized.size(),
            normalized.size(),
            plan.globalSeed(),
            plan.bucketSeeds(),
            plan.slotKeys(),
            plan.slotValues(),
            compileLookup(plan)
        );
    }

    int getOrDefault(String key, int defaultValue) {
        return lookup.getOrDefault(key, defaultValue);
    }

    int size() {
        return size;
    }

    int slotCount() {
        return slotCount;
    }

    int bucketCount() {
        return bucketSeeds.length;
    }

    boolean isMinimalPerfect() {
        return size == slotCount;
    }

    boolean usesGeneratedLookup() {
        return lookup instanceof GeneratedFrozenStringIntLookupBase;
    }

    String implementationName() {
        return lookup.getClass().getName();
    }

    int globalSeed() {
        return globalSeed;
    }

    int[] bucketSeeds() {
        return Arrays.copyOf(bucketSeeds, bucketSeeds.length);
    }

    String[] slotKeys() {
        return Arrays.copyOf(slotKeys, slotKeys.length);
    }

    int[] slotValues() {
        return Arrays.copyOf(slotValues, slotValues.length);
    }

    /**
     * Returns a Map of {@code key → slot} for use by
     * {@link org.intermed.core.registry.RegistryTranslationMatrix}.
     */
    java.util.Map<String, Integer> toKeySlotMap() {
        java.util.Map<String, Integer> map = new java.util.HashMap<>(slotKeys.length * 2);
        for (int i = 0; i < slotKeys.length; i++) {
            if (slotKeys[i] != null) {
                map.put(slotKeys[i], i);
            }
        }
        return map;
    }

    /**
     * Returns an ordered list of {@link RegistryTranslationMatrix.SlotEntry} for
     * use in the registry sync handshake.
     */
    java.util.List<RegistryTranslationMatrix.SlotEntry> toSlotEntryList() {
        java.util.List<RegistryTranslationMatrix.SlotEntry> list = new java.util.ArrayList<>(slotKeys.length);
        for (int i = 0; i < slotKeys.length; i++) {
            if (slotKeys[i] != null) {
                list.add(new RegistryTranslationMatrix.SlotEntry(i, slotKeys[i]));
            }
        }
        return list;
    }

    private static BuildPlan buildPlan(List<Entry> entries) {
        int[] bucketCountCandidates = bucketCountCandidates(entries.size());
        for (int bucketCount : bucketCountCandidates) {
            for (int globalSeed = 0; globalSeed < MAX_GLOBAL_SEED; globalSeed++) {
                BuildPlan plan = tryBuild(entries, globalSeed, bucketCount);
                if (plan != null) {
                    return plan;
                }
            }
        }
        throw new IllegalStateException(
            "Unable to build minimal perfect registry hash index for " + entries.size() + " entries"
        );
    }

    private static BuildPlan tryBuild(List<Entry> entries, int globalSeed, int bucketCount) {
        @SuppressWarnings("unchecked")
        List<Entry>[] buckets = new List[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new ArrayList<>();
        }

        for (Entry entry : entries) {
            buckets[primaryBucket(entry.key(), globalSeed, bucketCount)].add(entry);
        }

        Integer[] bucketOrder = new Integer[bucketCount];
        for (int i = 0; i < bucketOrder.length; i++) {
            bucketOrder[i] = i;
        }
        Arrays.sort(bucketOrder, Comparator
            .comparingInt((Integer idx) -> buckets[idx].size())
            .reversed());

        int slotCount = entries.size();
        int[] bucketSeeds = new int[bucketCount];
        String[] slotKeys = new String[slotCount];
        int[] slotValues = new int[slotCount];
        boolean[] occupied = new boolean[slotCount];

        for (int bucketIndex : bucketOrder) {
            List<Entry> bucket = buckets[bucketIndex];
            if (bucket.isEmpty() || bucket.size() == 1) {
                continue;
            }

            int[] candidateSlots = new int[bucket.size()];
            boolean placed = false;
            for (int seed = 1; seed < MAX_BUCKET_SEED; seed++) {
                if (!fitsBucket(bucket, globalSeed, seed, slotCount, occupied, candidateSlots)) {
                    continue;
                }
                for (int i = 0; i < bucket.size(); i++) {
                    Entry entry = bucket.get(i);
                    int slot = candidateSlots[i];
                    occupied[slot] = true;
                    slotKeys[slot] = entry.key();
                    slotValues[slot] = entry.value();
                }
                bucketSeeds[bucketIndex] = seed;
                placed = true;
                break;
            }

            if (!placed) {
                return null;
            }
        }

        int[] freeSlots = freeSlots(occupied);
        int freeCursor = 0;
        for (int bucketIndex : bucketOrder) {
            List<Entry> bucket = buckets[bucketIndex];
            if (bucket.isEmpty()) {
                continue;
            }
            if (bucket.size() > 1) {
                continue;
            }
            if (freeCursor >= freeSlots.length) {
                return null;
            }

            Entry entry = bucket.get(0);
            int slot = freeSlots[freeCursor++];
            occupied[slot] = true;
            slotKeys[slot] = entry.key();
            slotValues[slot] = entry.value();
            bucketSeeds[bucketIndex] = encodeDirectSlot(slot);
        }

        return new BuildPlan(globalSeed, bucketSeeds, slotKeys, slotValues);
    }

    private static FrozenStringIntLookup compileLookup(BuildPlan plan) {
        try {
            Constructor<? extends GeneratedFrozenStringIntLookupBase> constructor = generatedLookupConstructor();
            return constructor.newInstance(
                plan.globalSeed(),
                Arrays.copyOf(plan.bucketSeeds(), plan.bucketSeeds().length),
                Arrays.copyOf(plan.slotKeys(), plan.slotKeys().length),
                Arrays.copyOf(plan.slotValues(), plan.slotValues().length)
            );
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate compiled registry hash lookup", e);
        }
    }

    private static synchronized Constructor<? extends GeneratedFrozenStringIntLookupBase> generatedLookupConstructor() {
        if (generatedLookupConstructor != null) {
            return generatedLookupConstructor;
        }

        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                FrozenStringIntHashIndex.class,
                MethodHandles.lookup()
            );
            Constructor<GeneratedFrozenStringIntLookupBase> superConstructor =
                GeneratedFrozenStringIntLookupBase.class.getDeclaredConstructor(
                    int.class,
                    int[].class,
                    String[].class,
                    int[].class
                );

            DynamicType.Unloaded<? extends GeneratedFrozenStringIntLookupBase> unloaded = new ByteBuddy()
                .subclass(GeneratedFrozenStringIntLookupBase.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(GENERATED_LOOKUP_NAME)
                .modifiers(Visibility.PACKAGE_PRIVATE)
                .visit(new AsmVisitorWrapper.ForDeclaredMethods()
                    .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
                    .readerFlags(ClassReader.EXPAND_FRAMES))
                .defineConstructor(Visibility.PACKAGE_PRIVATE)
                .withParameters(int.class, int[].class, String[].class, int[].class)
                .intercept(MethodCall.invoke(superConstructor).onSuper().withAllArguments())
                .method(named("getOrDefault").and(takesArguments(String.class, int.class)))
                .intercept(new Implementation.Simple(new GeneratedLookupAppender()))
                .make();

            @SuppressWarnings("unchecked")
            Class<? extends GeneratedFrozenStringIntLookupBase> loaded = (Class<? extends GeneratedFrozenStringIntLookupBase>)
                unloaded
                    .load(
                        FrozenStringIntHashIndex.class.getClassLoader(),
                        ClassLoadingStrategy.UsingLookup.of(lookup)
                    )
                    .getLoaded();

            generatedLookupConstructor = loaded.getDeclaredConstructor(
                int.class,
                int[].class,
                String[].class,
                int[].class
            );
            generatedLookupConstructor.setAccessible(true);
            return generatedLookupConstructor;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to compile registry hash lookup with ByteBuddy", e);
        }
    }

    private static boolean fitsBucket(List<Entry> bucket,
                                      int globalSeed,
                                      int seed,
                                      int slotCount,
                                      boolean[] occupied,
                                      int[] candidateSlots) {
        for (int i = 0; i < bucket.size(); i++) {
            int slot = secondarySlot(bucket.get(i).key(), globalSeed, seed, slotCount);
            if (occupied[slot]) {
                return false;
            }
            for (int j = 0; j < i; j++) {
                if (candidateSlots[j] == slot) {
                    return false;
                }
            }
            candidateSlots[i] = slot;
        }
        return true;
    }

    private static int[] bucketCountCandidates(int size) {
        int minimal = nextPowerOfTwo(size);
        int widened = nextPowerOfTwo(size * 2);
        int aggressive = nextPowerOfTwo(size * 4);
        if (minimal == widened && widened == aggressive) {
            return new int[]{minimal};
        }
        if (minimal == widened) {
            return new int[]{minimal, aggressive};
        }
        if (widened == aggressive) {
            return new int[]{minimal, widened};
        }
        return new int[]{minimal, widened, aggressive};
    }

    private static int[] freeSlots(boolean[] occupied) {
        int free = 0;
        for (boolean slot : occupied) {
            if (!slot) {
                free++;
            }
        }
        int[] result = new int[free];
        int cursor = 0;
        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i]) {
                result[cursor++] = i;
            }
        }
        return result;
    }

    static int primaryBucket(String key, int globalSeed, int bucketCount) {
        return positiveMod(mix64(key, 0x9E3779B97F4A7C15L ^ globalSeed), bucketCount);
    }

    static int secondarySlot(String key, int globalSeed, int seed, int slotCount) {
        long salt = 0xC2B2AE3D27D4EB4FL ^ ((long) globalSeed << 32) ^ Integer.toUnsignedLong(seed);
        return positiveMod(mix64(key, salt), slotCount);
    }

    private static long mix64(String key, long salt) {
        long hash = salt ^ 0xCBF29CE484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001B3L;
            hash ^= (hash >>> 32);
        }
        hash ^= (hash >>> 33);
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= (hash >>> 33);
        hash *= 0xC4CEB9FE1A85EC53L;
        hash ^= (hash >>> 33);
        return hash & Long.MAX_VALUE;
    }

    private static int positiveMod(long value, int bound) {
        if (bound <= 0) {
            return 0;
        }
        return (int) Long.remainderUnsigned(value, bound);
    }

    private static int encodeDirectSlot(int slot) {
        return -slot - 1;
    }

    static int decodeDirectSlot(int encoded) {
        return -encoded - 1;
    }

    private static int nextPowerOfTwo(int value) {
        int candidate = 1;
        while (candidate < Math.max(1, value)) {
            candidate <<= 1;
        }
        return candidate;
    }

    private interface FrozenStringIntLookup {
        int getOrDefault(String key, int defaultValue);
    }

    private enum EmptyLookup implements FrozenStringIntLookup {
        INSTANCE;

        @Override
        public int getOrDefault(String key, int defaultValue) {
            return defaultValue;
        }
    }

    static abstract class GeneratedFrozenStringIntLookupBase implements FrozenStringIntLookup {
        protected final int globalSeed;
        protected final int[] bucketSeeds;
        protected final String[] slotKeys;
        protected final int[] slotValues;

        GeneratedFrozenStringIntLookupBase(int globalSeed,
                                           int[] bucketSeeds,
                                           String[] slotKeys,
                                           int[] slotValues) {
            this.globalSeed = globalSeed;
            this.bucketSeeds = bucketSeeds;
            this.slotKeys = slotKeys;
            this.slotValues = slotValues;
        }

        @Override
        public abstract int getOrDefault(String key, int defaultValue);
    }

    private static final class GeneratedLookupAppender implements ByteCodeAppender {
        private static final String SELF = Type.getInternalName(GeneratedFrozenStringIntLookupBase.class);
        private static final String OWNER = Type.getInternalName(FrozenStringIntHashIndex.class);
        private static final String STRING = Type.getInternalName(String.class);
        private static final String INT_ARRAY_DESC = Type.getDescriptor(int[].class);
        private static final String STRING_ARRAY_DESC = Type.getDescriptor(String[].class);
        private static final String PRIMARY_BUCKET_DESC =
            Type.getMethodDescriptor(Type.INT_TYPE, Type.getType(String.class), Type.INT_TYPE, Type.INT_TYPE);
        private static final String SECONDARY_SLOT_DESC =
            Type.getMethodDescriptor(Type.INT_TYPE, Type.getType(String.class), Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE);
        private static final String DIRECT_SLOT_DESC =
            Type.getMethodDescriptor(Type.INT_TYPE, Type.INT_TYPE);

        @Override
        public Size apply(MethodVisitor visitor,
                          Implementation.Context implementationContext,
                          net.bytebuddy.description.method.MethodDescription instrumentedMethod) {
            Label keyPresent = new Label();
            Label keyNotBlank = new Label();
            Label hasEntries = new Label();
            Label hasSeed = new Label();
            Label directSlot = new Label();
            Label slotResolved = new Label();
            Label slotInRange = new Label();
            Label returnDefault = new Label();

            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitJumpInsn(Opcodes.IFNONNULL, keyPresent);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);

            visitor.visitLabel(keyPresent);
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING, "isBlank", "()Z", false);
            visitor.visitJumpInsn(Opcodes.IFEQ, keyNotBlank);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);

            visitor.visitLabel(keyNotBlank);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "slotKeys", STRING_ARRAY_DESC);
            visitor.visitInsn(Opcodes.ARRAYLENGTH);
            visitor.visitJumpInsn(Opcodes.IFNE, hasEntries);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);

            visitor.visitLabel(hasEntries);
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "globalSeed", "I");
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "bucketSeeds", INT_ARRAY_DESC);
            visitor.visitInsn(Opcodes.ARRAYLENGTH);
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "primaryBucket", PRIMARY_BUCKET_DESC, false);
            visitor.visitVarInsn(Opcodes.ISTORE, 3);

            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "bucketSeeds", INT_ARRAY_DESC);
            visitor.visitVarInsn(Opcodes.ILOAD, 3);
            visitor.visitInsn(Opcodes.IALOAD);
            visitor.visitVarInsn(Opcodes.ISTORE, 4);

            visitor.visitVarInsn(Opcodes.ILOAD, 4);
            visitor.visitJumpInsn(Opcodes.IFNE, hasSeed);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);

            visitor.visitLabel(hasSeed);
            visitor.visitVarInsn(Opcodes.ILOAD, 4);
            visitor.visitJumpInsn(Opcodes.IFLT, directSlot);
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "globalSeed", "I");
            visitor.visitVarInsn(Opcodes.ILOAD, 4);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "slotKeys", STRING_ARRAY_DESC);
            visitor.visitInsn(Opcodes.ARRAYLENGTH);
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "secondarySlot", SECONDARY_SLOT_DESC, false);
            visitor.visitVarInsn(Opcodes.ISTORE, 5);
            visitor.visitJumpInsn(Opcodes.GOTO, slotResolved);

            visitor.visitLabel(directSlot);
            visitor.visitVarInsn(Opcodes.ILOAD, 4);
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "decodeDirectSlot", DIRECT_SLOT_DESC, false);
            visitor.visitVarInsn(Opcodes.ISTORE, 5);

            visitor.visitLabel(slotResolved);
            visitor.visitVarInsn(Opcodes.ILOAD, 5);
            visitor.visitJumpInsn(Opcodes.IFLT, returnDefault);
            visitor.visitVarInsn(Opcodes.ILOAD, 5);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "slotKeys", STRING_ARRAY_DESC);
            visitor.visitInsn(Opcodes.ARRAYLENGTH);
            visitor.visitJumpInsn(Opcodes.IF_ICMPLT, slotInRange);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);

            visitor.visitLabel(slotInRange);
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "slotKeys", STRING_ARRAY_DESC);
            visitor.visitVarInsn(Opcodes.ILOAD, 5);
            visitor.visitInsn(Opcodes.AALOAD);
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING, "equals", "(Ljava/lang/Object;)Z", false);
            visitor.visitJumpInsn(Opcodes.IFEQ, returnDefault);
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            visitor.visitFieldInsn(Opcodes.GETFIELD, SELF, "slotValues", INT_ARRAY_DESC);
            visitor.visitVarInsn(Opcodes.ILOAD, 5);
            visitor.visitInsn(Opcodes.IALOAD);
            visitor.visitInsn(Opcodes.IRETURN);

            visitor.visitLabel(returnDefault);
            visitor.visitVarInsn(Opcodes.ILOAD, 2);
            visitor.visitInsn(Opcodes.IRETURN);

            return new Size(5, 6);
        }
    }

    private record BuildPlan(int globalSeed, int[] bucketSeeds, String[] slotKeys, int[] slotValues) {}

    private record Entry(String key, int value) {}
}
