package org.intermed.core.monitor;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import org.intermed.core.event.MainThreadAffinityDispatcher;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Lock-free event bus backed by a preallocated ring buffer and compiled
 * unrolled dispatch plans.
 *
 * <p>Hot-path publication uses atomics only: producers claim a sequence, write
 * into a preallocated slot, then trigger a drain loop guarded by an atomic
 * work-in-progress counter. The active listener snapshot and its compiled
 * dispatcher are swapped atomically as a single immutable state object.
 *
 * <p>When listeners are registered, the bus generates a synthetic dispatcher
 * class through ByteBuddy. The generated dispatcher contains a direct,
 * sequential call-site for every current listener instead of iterating a list,
 * which keeps the steady-state dispatch path monomorphic-friendly for the JIT.
 */
public final class LockFreeEvent<T> {

    private static final int DEFAULT_CAPACITY = 1_024;
    private static final int DEFAULT_GENERATED_DISPATCHER_CACHE_SIZE = 256;
    private static final ListenerRegistration<?>[] EMPTY_LISTENERS = new ListenerRegistration[0];
    private static final EmptyDispatchPlan EMPTY_DISPATCH_PLAN = EmptyDispatchPlan.INSTANCE;
    private static final AtomicLong GENERATED_DISPATCHER_ID = new AtomicLong();
    private static final Object GENERATED_DISPATCHERS_LOCK = new Object();
    private static final LinkedHashMap<DispatchSignature, Constructor<? extends GeneratedDispatchPlanBase>>
        GENERATED_DISPATCHERS = new LinkedHashMap<>(16, 0.75f, true);

    private final RingSlot[] ring;
    private final int mask;
    private final Supplier<String> modIdSupplier;
    private final DispatchMetadata<T> dispatchMetadata;
    private final PublicationObserver publicationObserver;
    private final MainThreadAffinityDispatcher.EventAffinity affinity;
    private final AtomicReference<EventState<T>> state;
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong consumedSequence = new AtomicLong(-1);
    private final AtomicInteger drainWip = new AtomicInteger();
    private final T invoker;

    public LockFreeEvent(Class<T> listenerType,
                         Supplier<String> modIdSupplier,
                         InvokerFactory<T> invokerFactory) {
        this(resolveConfiguredCapacity(), listenerType, modIdSupplier,
            MainThreadAffinityDispatcher.EventAffinity.LOGIC, invokerFactory);
    }

    public LockFreeEvent(Class<T> listenerType,
                         Supplier<String> modIdSupplier,
                         MainThreadAffinityDispatcher.EventAffinity affinity,
                         InvokerFactory<T> invokerFactory) {
        this(resolveConfiguredCapacity(), listenerType, modIdSupplier, affinity, null, invokerFactory);
    }

    static <T> LockFreeEvent<T> createForTests(int capacity,
                                               Class<T> listenerType,
                                               Supplier<String> modIdSupplier,
                                               InvokerFactory<T> invokerFactory) {
        return new LockFreeEvent<>(capacity, listenerType, modIdSupplier,
            MainThreadAffinityDispatcher.EventAffinity.LOGIC, null, invokerFactory);
    }

    static <T> LockFreeEvent<T> createForTests(int capacity,
                                               Class<T> listenerType,
                                               Supplier<String> modIdSupplier,
                                               PublicationObserver publicationObserver,
                                               InvokerFactory<T> invokerFactory) {
        return new LockFreeEvent<>(capacity, listenerType, modIdSupplier,
            MainThreadAffinityDispatcher.EventAffinity.LOGIC, publicationObserver, invokerFactory);
    }

    private LockFreeEvent(int capacity,
                          Class<T> listenerType,
                          Supplier<String> modIdSupplier,
                          MainThreadAffinityDispatcher.EventAffinity affinity,
                          InvokerFactory<T> invokerFactory) {
        this(capacity, listenerType, modIdSupplier, affinity, null, invokerFactory);
    }

    private LockFreeEvent(int capacity,
                          Class<T> listenerType,
                          Supplier<String> modIdSupplier,
                          MainThreadAffinityDispatcher.EventAffinity affinity,
                          PublicationObserver publicationObserver,
                          InvokerFactory<T> invokerFactory) {
        int normalizedCapacity = normalizeCapacity(capacity);
        this.ring = new RingSlot[normalizedCapacity];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new RingSlot();
        }
        this.mask = normalizedCapacity - 1;
        this.modIdSupplier = modIdSupplier;
        this.dispatchMetadata = DispatchMetadata.from(listenerType);
        this.affinity = affinity != null ? affinity : MainThreadAffinityDispatcher.EventAffinity.LOGIC;
        this.publicationObserver = publicationObserver;
        this.state = new AtomicReference<>(emptyState());
        this.invoker = invokerFactory.create(this::publish);
    }

    public void register(T listener) {
        if (listener == null) {
            return;
        }

        dispatchMetadata.validateListener(listener);
        ListenerRegistration<T> registration = new ListenerRegistration<>(
            normalizeModId(modIdSupplier.get()),
            listener
        );
        while (true) {
            EventState<T> current = state.get();
            ListenerRegistration<T>[] updatedListeners =
                Arrays.copyOf(current.listeners(), current.listeners().length + 1);
            updatedListeners[current.listeners().length] = registration;
            EventState<T> updatedState = new EventState<>(updatedListeners, buildDispatchPlan(updatedListeners));
            if (state.compareAndSet(current, updatedState)) {
                return;
            }
        }
    }

    public T invoker() {
        return invoker;
    }

    public void clear() {
        state.set(emptyState());
        nextSequence.set(0L);
        consumedSequence.set(-1L);
        drainWip.set(0);
        for (RingSlot slot : ring) {
            slot.clear();
        }
    }

    public boolean usesGeneratedDispatcher() {
        return state.get().dispatchPlan().isGenerated();
    }

    public String dispatcherImplementationName() {
        return state.get().dispatchPlan().implementationName();
    }

    public int listenerCount() {
        return state.get().listeners().length;
    }

    int capacity() {
        return ring.length;
    }

    public MainThreadAffinityDispatcher.EventAffinity affinity() {
        return affinity;
    }

    long publishedSequence() {
        return nextSequence.get() - 1L;
    }

    long consumedSequence() {
        return consumedSequence.get();
    }

    private void publish(Object arg0, Object arg1, Object arg2) {
        long sequence = nextSequence.getAndIncrement();
        while (sequence - consumedSequence.get() >= ring.length) {
            drain();
            Thread.onSpinWait();
        }

        DispatchPlan dispatchPlan = state.get().dispatchPlan();
        RingSlot slot = ring[indexOf(sequence)];
        slot.store(dispatchPlan, arg0, arg1, arg2, sequence);
        if (publicationObserver != null) {
            publicationObserver.afterStore(sequence);
        }
        drain();

        while (consumedSequence.get() < sequence) {
            drain();
            Thread.onSpinWait();
        }
    }

    private void drain() {
        if (drainWip.getAndIncrement() != 0) {
            return;
        }

        int missed = 1;
        do {
            long next = consumedSequence.get() + 1L;
            while (true) {
                RingSlot slot = ring[indexOf(next)];
                if (!slot.isPublished(next)) {
                    break;
                }
                dispatch(slot);
                slot.clear();
                consumedSequence.lazySet(next);
                next++;
            }
            missed = drainWip.addAndGet(-missed);
        } while (missed != 0);
    }

    private void dispatch(RingSlot slot) {
        if (affinity == MainThreadAffinityDispatcher.EventAffinity.RENDER) {
            final DispatchPlan plan = slot.dispatchPlan;
            final Object a0 = slot.arg0, a1 = slot.arg1, a2 = slot.arg2;
            MainThreadAffinityDispatcher.dispatch(affinity, () -> plan.dispatch(a0, a1, a2));
        } else {
            slot.dispatchPlan.dispatch(slot.arg0, slot.arg1, slot.arg2);
        }
    }

    private EventState<T> emptyState() {
        return new EventState<>(emptyListeners(), EMPTY_DISPATCH_PLAN);
    }

    private DispatchPlan buildDispatchPlan(ListenerRegistration<T>[] listeners) {
        if (listeners.length == 0) {
            return EMPTY_DISPATCH_PLAN;
        }
        if (!dispatchMetadata.canGenerate()) {
            return new InterpretedDispatchPlan<>(dispatchMetadata, listeners);
        }

        try {
            DispatchSignature signature = new DispatchSignature(
                dispatchMetadata.listenerType(),
                dispatchMetadata.methodName(),
                dispatchMetadata.methodDescriptor(),
                listeners.length
            );
            Constructor<? extends GeneratedDispatchPlanBase> constructor = resolveGeneratedDispatcher(signature);
            if (constructor == null) {
                return new InterpretedDispatchPlan<>(dispatchMetadata, listeners);
            }
            return constructor.newInstance(extractModIds(listeners), extractListeners(listeners));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            return new InterpretedDispatchPlan<>(dispatchMetadata, listeners);
        }
    }

    private Constructor<? extends GeneratedDispatchPlanBase> compileDispatcherConstructor(DispatchSignature signature) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                dispatchMetadata.lookupHost(),
                MethodHandles.lookup()
            );
            Constructor<GeneratedDispatchPlanBase> superConstructor =
                GeneratedDispatchPlanBase.class.getDeclaredConstructor(String[].class, Object[].class);

            DynamicType.Unloaded<? extends GeneratedDispatchPlanBase> unloaded = new ByteBuddy()
                .subclass(GeneratedDispatchPlanBase.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(generatedClassName(signature))
                .modifiers(Visibility.PACKAGE_PRIVATE)
                .visit(new AsmVisitorWrapper.ForDeclaredMethods()
                    .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
                    .readerFlags(ClassReader.EXPAND_FRAMES))
                .defineConstructor(Visibility.PACKAGE_PRIVATE)
                .withParameters(String[].class, Object[].class)
                .intercept(MethodCall.invoke(superConstructor).onSuper().withAllArguments())
                .method(named("dispatch").and(takesArguments(Object.class, Object.class, Object.class)))
                .intercept(new Implementation.Simple(
                    new GeneratedDispatchAppender(dispatchMetadata, signature.listenerCount())
                ))
                .make();

            @SuppressWarnings("unchecked")
            Class<? extends GeneratedDispatchPlanBase> loaded =
                (Class<? extends GeneratedDispatchPlanBase>) unloaded
                    .load(
                        dispatchMetadata.listenerType().getClassLoader(),
                        ClassLoadingStrategy.UsingLookup.of(lookup)
                    )
                    .getLoaded();

            Constructor<? extends GeneratedDispatchPlanBase> constructor =
                loaded.getDeclaredConstructor(String[].class, Object[].class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to compile lock-free event dispatcher", e);
        }
    }

    private Constructor<? extends GeneratedDispatchPlanBase> resolveGeneratedDispatcher(DispatchSignature signature)
        throws ReflectiveOperationException {
        synchronized (GENERATED_DISPATCHERS_LOCK) {
            Constructor<? extends GeneratedDispatchPlanBase> cached = GENERATED_DISPATCHERS.get(signature);
            if (cached != null) {
                return cached;
            }
            if (GENERATED_DISPATCHERS.size() >= resolveConfiguredDispatcherCacheSize()) {
                return null;
            }
            Constructor<? extends GeneratedDispatchPlanBase> compiled = compileDispatcherConstructor(signature);
            GENERATED_DISPATCHERS.put(signature, compiled);
            return compiled;
        }
    }

    private String generatedClassName(DispatchSignature signature) {
        String packageName = signature.listenerType().getPackageName();
        String sanitizedListener = signature.listenerType()
            .getName()
            .replace('.', '_')
            .replace('$', '_');
        String simpleName = "CompiledLockFreeEventDispatcher$"
            + sanitizedListener
            + "$"
            + signature.listenerCount()
            + "$"
            + GENERATED_DISPATCHER_ID.incrementAndGet();
        return packageName == null || packageName.isBlank()
            ? simpleName
            : packageName + "." + simpleName;
    }

    private static String[] extractModIds(ListenerRegistration<?>[] listeners) {
        String[] modIds = new String[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            modIds[i] = listeners[i].modId();
        }
        return modIds;
    }

    private static Object[] extractListeners(ListenerRegistration<?>[] listeners) {
        Object[] values = new Object[listeners.length];
        for (int i = 0; i < listeners.length; i++) {
            values[i] = listeners[i].listener();
        }
        return values;
    }

    private int indexOf(long sequence) {
        return (int) sequence & mask;
    }

    private static int resolveConfiguredCapacity() {
        return Integer.getInteger("intermed.events.ring-buffer.capacity", DEFAULT_CAPACITY);
    }

    private static int resolveConfiguredDispatcherCacheSize() {
        return Math.max(0, Integer.getInteger(
            "intermed.events.dispatcher-cache.max-size",
            DEFAULT_GENERATED_DISPATCHER_CACHE_SIZE
        ));
    }

    private static int normalizeCapacity(int capacity) {
        int normalized = Math.max(2, capacity);
        if ((normalized & (normalized - 1)) == 0) {
            return normalized;
        }

        int powerOfTwo = 1;
        while (powerOfTwo < normalized) {
            powerOfTwo <<= 1;
        }
        return powerOfTwo;
    }

    private static String normalizeModId(String modId) {
        return modId == null || modId.isBlank() ? "unknown" : modId;
    }

    @SuppressWarnings("unchecked")
    private static <T> ListenerRegistration<T>[] emptyListeners() {
        return (ListenerRegistration<T>[]) EMPTY_LISTENERS;
    }

    @FunctionalInterface
    public interface InvokerFactory<T> {
        T create(Publisher publisher);
    }

    @FunctionalInterface
    public interface Publisher {
        void publish(Object arg0, Object arg1, Object arg2);
    }

    @FunctionalInterface
    interface PublicationObserver {
        void afterStore(long sequence);
    }

    static void resetGeneratedDispatcherCacheForTests() {
        synchronized (GENERATED_DISPATCHERS_LOCK) {
            GENERATED_DISPATCHERS.clear();
        }
    }

    static int generatedDispatcherCacheSizeForTests() {
        synchronized (GENERATED_DISPATCHERS_LOCK) {
            return GENERATED_DISPATCHERS.size();
        }
    }

    private interface DispatchPlan {
        void dispatch(Object arg0, Object arg1, Object arg2);

        boolean isGenerated();

        String implementationName();
    }

    public static abstract class GeneratedDispatchPlanBase implements DispatchPlan {
        protected final String[] modIds;
        protected final Object[] listeners;

        protected GeneratedDispatchPlanBase(String[] modIds, Object[] listeners) {
            this.modIds = Objects.requireNonNull(modIds, "modIds");
            this.listeners = Objects.requireNonNull(listeners, "listeners");
        }

        @Override
        public boolean isGenerated() {
            return true;
        }

        @Override
        public String implementationName() {
            return getClass().getName();
        }
    }

    private enum EmptyDispatchPlan implements DispatchPlan {
        INSTANCE;

        @Override
        public void dispatch(Object arg0, Object arg1, Object arg2) {
        }

        @Override
        public boolean isGenerated() {
            return false;
        }

        @Override
        public String implementationName() {
            return getClass().getName();
        }
    }

    private record EventState<T>(ListenerRegistration<T>[] listeners, DispatchPlan dispatchPlan) {}

    private record ListenerRegistration<T>(String modId, T listener) {}

    private record DispatchSignature(Class<?> listenerType,
                                     String methodName,
                                     String methodDescriptor,
                                     int listenerCount) {}

    private static final class InterpretedDispatchPlan<T> implements DispatchPlan {
        private final DispatchMetadata<T> metadata;
        private final ListenerRegistration<T>[] listeners;

        private InterpretedDispatchPlan(DispatchMetadata<T> metadata, ListenerRegistration<T>[] listeners) {
            this.metadata = metadata;
            this.listeners = listeners;
        }

        @Override
        public void dispatch(Object arg0, Object arg1, Object arg2) {
            for (ListenerRegistration<T> registration : listeners) {
                if (ObservabilityMonitor.isModThrottled(registration.modId())) {
                    continue;
                }
                metadata.invoke(registration.listener(), arg0, arg1, arg2);
            }
        }

        @Override
        public boolean isGenerated() {
            return false;
        }

        @Override
        public String implementationName() {
            return getClass().getName();
        }
    }

    private static final class DispatchMetadata<T> {
        private final Class<T> listenerType;
        private final Method listenerMethod;
        private final Class<?>[] parameterTypes;
        private final String methodName;
        private final String methodDescriptor;

        private DispatchMetadata(Class<T> listenerType, Method listenerMethod) {
            this.listenerType = listenerType;
            this.listenerMethod = listenerMethod;
            this.parameterTypes = listenerMethod.getParameterTypes();
            this.methodName = listenerMethod.getName();
            this.methodDescriptor = Type.getMethodDescriptor(listenerMethod);
        }

        private static <T> DispatchMetadata<T> from(Class<T> listenerType) {
            Method listenerMethod = findSingleAbstractMethod(listenerType);
            if (listenerMethod.getParameterCount() > 3) {
                throw new IllegalArgumentException(
                    "LockFreeEvent supports listener methods with up to 3 parameters: " + listenerMethod
                );
            }
            return new DispatchMetadata<>(listenerType, listenerMethod);
        }

        private static Method findSingleAbstractMethod(Class<?> listenerType) {
            Method resolved = null;
            for (Method method : listenerType.getMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                if (method.getDeclaringClass() == Object.class || method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                if (resolved != null && !sameSignature(resolved, method)) {
                    throw new IllegalArgumentException(
                        "Listener type must expose a single abstract dispatch method: " + listenerType.getName()
                    );
                }
                resolved = method;
            }
            if (resolved == null) {
                throw new IllegalArgumentException(
                    "Listener type does not expose an abstract dispatch method: " + listenerType.getName()
                );
            }
            return resolved;
        }

        private static boolean sameSignature(Method left, Method right) {
            return left.getName().equals(right.getName())
                && Arrays.equals(left.getParameterTypes(), right.getParameterTypes())
                && left.getReturnType().equals(right.getReturnType());
        }

        private boolean canGenerate() {
            int listenerModifiers = listenerType.getModifiers();
            int methodModifiers = listenerMethod.getModifiers();
            if (Modifier.isPrivate(listenerModifiers) || Modifier.isPrivate(methodModifiers)) {
                return false;
            }
            if (listenerType.isAnonymousClass() || listenerType.isLocalClass()) {
                return false;
            }
            return true;
        }

        private void validateListener(T listener) {
            if (listenerMethod.canAccess(listener) || listenerMethod.trySetAccessible()) {
                return;
            }
            throw new IllegalArgumentException(
                "Listener method is not accessible for fallback dispatch: " + listenerMethod
            );
        }

        private void invoke(T listener, Object arg0, Object arg1, Object arg2) {
            try {
                if (!listenerMethod.canAccess(listener) && !listenerMethod.trySetAccessible()) {
                    throw new IllegalStateException(
                        "Fallback listener became inaccessible during dispatch: " + listenerMethod
                    );
                }
                Object[] args = switch (parameterTypes.length) {
                    case 0 -> new Object[0];
                    case 1 -> new Object[]{arg0};
                    case 2 -> new Object[]{arg0, arg1};
                    case 3 -> new Object[]{arg0, arg1, arg2};
                    default -> throw new IllegalStateException("Unsupported listener arity: " + parameterTypes.length);
                };
                listenerMethod.invoke(listener, args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to invoke fallback event listener", e);
            }
        }

        private Class<T> listenerType() {
            return listenerType;
        }

        private Method listenerMethod() {
            return listenerMethod;
        }

        private Class<?>[] parameterTypes() {
            return parameterTypes;
        }

        private String methodName() {
            return methodName;
        }

        private String methodDescriptor() {
            return methodDescriptor;
        }

        private Class<?> lookupHost() {
            return listenerType;
        }
    }

    private static final class GeneratedDispatchAppender implements ByteCodeAppender {
        private static final String BASE = Type.getInternalName(GeneratedDispatchPlanBase.class);
        private static final String MOD_IDS_DESC = Type.getDescriptor(String[].class);
        private static final String LISTENERS_DESC = Type.getDescriptor(Object[].class);
        private static final String OBSERVABILITY = Type.getInternalName(ObservabilityMonitor.class);
        private static final String SHOULD_SKIP_DESC = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(String.class));

        private final String listenerInternalName;
        private final String methodName;
        private final String methodDescriptor;
        private final Class<?>[] parameterTypes;
        private final int listenerCount;

        private GeneratedDispatchAppender(DispatchMetadata<?> metadata, int listenerCount) {
            this.listenerInternalName = Type.getInternalName(metadata.listenerType());
            this.methodName = metadata.methodName();
            this.methodDescriptor = metadata.methodDescriptor();
            this.parameterTypes = metadata.parameterTypes();
            this.listenerCount = listenerCount;
        }

        @Override
        public Size apply(MethodVisitor visitor,
                          Implementation.Context implementationContext,
                          net.bytebuddy.description.method.MethodDescription instrumentedMethod) {
            for (int i = 0; i < listenerCount; i++) {
                Label skipListener = new Label();
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                visitor.visitFieldInsn(Opcodes.GETFIELD, BASE, "modIds", MOD_IDS_DESC);
                pushInt(visitor, i);
                visitor.visitInsn(Opcodes.AALOAD);
                visitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    OBSERVABILITY,
                    "isModThrottled",
                    SHOULD_SKIP_DESC,
                    false
                );
                visitor.visitJumpInsn(Opcodes.IFNE, skipListener);

                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                visitor.visitFieldInsn(Opcodes.GETFIELD, BASE, "listeners", LISTENERS_DESC);
                pushInt(visitor, i);
                visitor.visitInsn(Opcodes.AALOAD);
                visitor.visitTypeInsn(Opcodes.CHECKCAST, listenerInternalName);

                for (int parameterIndex = 0; parameterIndex < parameterTypes.length; parameterIndex++) {
                    emitArgumentLoad(visitor, parameterIndex + 1, parameterTypes[parameterIndex]);
                }

                visitor.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    listenerInternalName,
                    methodName,
                    methodDescriptor,
                    true
                );
                visitor.visitLabel(skipListener);
            }

            visitor.visitInsn(Opcodes.RETURN);
            return new Size(10, 4);
        }

        private static void emitArgumentLoad(MethodVisitor visitor, int localIndex, Class<?> parameterType) {
            visitor.visitVarInsn(Opcodes.ALOAD, localIndex);
            if (!parameterType.isPrimitive()) {
                visitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(parameterType));
                return;
            }

            String owner;
            String method;
            String descriptor;
            if (parameterType == boolean.class) {
                owner = Type.getInternalName(Boolean.class);
                method = "booleanValue";
                descriptor = "()Z";
            } else if (parameterType == byte.class) {
                owner = Type.getInternalName(Byte.class);
                method = "byteValue";
                descriptor = "()B";
            } else if (parameterType == short.class) {
                owner = Type.getInternalName(Short.class);
                method = "shortValue";
                descriptor = "()S";
            } else if (parameterType == char.class) {
                owner = Type.getInternalName(Character.class);
                method = "charValue";
                descriptor = "()C";
            } else if (parameterType == int.class) {
                owner = Type.getInternalName(Integer.class);
                method = "intValue";
                descriptor = "()I";
            } else if (parameterType == long.class) {
                owner = Type.getInternalName(Long.class);
                method = "longValue";
                descriptor = "()J";
            } else if (parameterType == float.class) {
                owner = Type.getInternalName(Float.class);
                method = "floatValue";
                descriptor = "()F";
            } else if (parameterType == double.class) {
                owner = Type.getInternalName(Double.class);
                method = "doubleValue";
                descriptor = "()D";
            } else {
                throw new IllegalArgumentException("Unsupported primitive listener parameter: " + parameterType.getName());
            }

            visitor.visitTypeInsn(Opcodes.CHECKCAST, owner);
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, method, descriptor, false);
        }

        private static void pushInt(MethodVisitor visitor, int value) {
            if (value >= -1 && value <= 5) {
                visitor.visitInsn(Opcodes.ICONST_0 + value);
                return;
            }
            if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                visitor.visitIntInsn(Opcodes.BIPUSH, value);
                return;
            }
            if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                visitor.visitIntInsn(Opcodes.SIPUSH, value);
                return;
            }
            visitor.visitLdcInsn(value);
        }
    }

    private static final class RingSlot {
        private volatile long sequence = Long.MIN_VALUE;
        private DispatchPlan dispatchPlan;
        private Object arg0;
        private Object arg1;
        private Object arg2;

        private void store(DispatchPlan dispatchPlan, Object arg0, Object arg1, Object arg2, long sequence) {
            this.dispatchPlan = dispatchPlan;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.arg2 = arg2;
            this.sequence = sequence;
        }

        private boolean isPublished(long expectedSequence) {
            return sequence == expectedSequence;
        }

        private void clear() {
            dispatchPlan = null;
            arg0 = null;
            arg1 = null;
            arg2 = null;
            sequence = Long.MIN_VALUE;
        }
    }
}
