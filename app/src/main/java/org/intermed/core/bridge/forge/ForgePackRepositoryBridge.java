package org.intermed.core.bridge.forge;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ForgePackRepositoryBridge {
    private static final AtomicInteger SOURCE_COUNT = new AtomicInteger();

    private ForgePackRepositoryBridge() {}

    public static void installIfPackFinderEvent(Object event) {
        if (event == null) {
            return;
        }
        if (!"net.minecraftforge.event.AddPackFindersEvent".equals(event.getClass().getName())) {
            return;
        }
        install(event);
    }

    public static void install(Object event) {
        if (event == null || !Boolean.parseBoolean(System.getProperty("intermed.forge.pack.bridge", "true"))) {
            return;
        }

        try {
            Object packType = invokeNoArg(event, "getPackType");
            if (packType == null) {
                return;
            }

            ClassLoader loader = chooseLoader(event.getClass().getClassLoader());
            Class<?> repositorySourceClass = Class.forName(
                "net.minecraft.server.packs.repository.RepositorySource", false, loader);
            Object source = createRepositorySource(loader, packType);

            Method addRepositorySource = event.getClass().getMethod("addRepositorySource", repositorySourceClass);
            addRepositorySource.invoke(event, source);
            int count = SOURCE_COUNT.incrementAndGet();
            System.out.printf("[ResourceBridge] InterMed RepositorySource attached for %s (#%d).%n",
                packType, count);
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("[ResourceBridge] Failed to attach RepositorySource: "
            + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static void injectIntoPackRepository(Object repository) {
        if (repository == null || !Boolean.parseBoolean(System.getProperty("intermed.forge.pack.bridge", "true"))) {
            return;
        }

        try {
            ClassLoader loader = chooseLoader(repository.getClass().getClassLoader());
            Class<?> packTypeClass = Class.forName("net.minecraft.server.packs.PackType", false, loader);
            Object clientResources = enumConstant(packTypeClass, "CLIENT_RESOURCES");
            Object serverData = enumConstant(packTypeClass, "SERVER_DATA");

            if (tryAddPackFinder(repository, loader, clientResources)
                | tryAddPackFinder(repository, loader, serverData)) {
                return;
            }

            Field sourcesField = findRepositorySourcesField(repository.getClass());
            sourcesField.setAccessible(true);
            Object value = sourcesField.get(repository);
            if (!(value instanceof Set<?> sources)) {
                System.err.println("[ResourceBridge] PackRepository source field is not a Set: "
                    + (value == null ? "null" : value.getClass().getName()));
                return;
            }

            Set<?> merged = withInterMedSources(repository, sources);
            if (merged != sources) {
                sourcesField.set(repository, merged);
            }
        } catch (ReflectiveOperationException | SecurityException | LinkageError e) {
            System.err.println("[ResourceBridge] Failed to inject PackRepository source: "
                + describeThrowable(e));
        }
    }

    public static Set<?> withInterMedSources(Object repository, Set<?> sources) {
        if (repository == null || sources == null
            || !Boolean.parseBoolean(System.getProperty("intermed.forge.pack.bridge", "true"))) {
            return sources;
        }

        try {
            ClassLoader loader = chooseLoader(repository.getClass().getClassLoader());
            LinkedHashSet<Object> merged = new LinkedHashSet<>(sources);
            Class<?> packTypeClass = Class.forName("net.minecraft.server.packs.PackType", false, loader);
            Object clientResources = enumConstant(packTypeClass, "CLIENT_RESOURCES");
            Object serverData = enumConstant(packTypeClass, "SERVER_DATA");
            merged.add(createRepositorySource(loader, clientResources));
            merged.add(createRepositorySource(loader, serverData));
            System.out.printf("[ResourceBridge] InterMed RepositorySource injected into PackRepository (%d -> %d source(s)).%n",
                sources.size(), merged.size());
            return merged;
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("[ResourceBridge] Failed to inject PackRepository source: "
                + describeThrowable(e));
            return sources;
        }
    }

    private static boolean tryAddPackFinder(Object repository, ClassLoader loader, Object packType)
        throws ReflectiveOperationException {
        Class<?> repositorySourceClass = Class.forName(
            "net.minecraft.server.packs.repository.RepositorySource", false, loader);
        Method addPackFinder;
        try {
            addPackFinder = repository.getClass().getMethod("addPackFinder", repositorySourceClass);
        } catch (NoSuchMethodException e) {
            return false;
        }

        addPackFinder.invoke(repository, createRepositorySource(loader, packType));
        int count = SOURCE_COUNT.incrementAndGet();
        System.out.printf("[ResourceBridge] InterMed RepositorySource added to PackRepository for %s (#%d).%n",
            packType, count);
        return true;
    }

    private static Field findRepositorySourcesField(Class<?> repositoryClass) throws NoSuchFieldException {
        for (String fieldName : List.of("f_10497_", "sources", "repositorySources")) {
            try {
                Field field = repositoryClass.getDeclaredField(fieldName);
                if (Set.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Try the next known mapping name.
            }
        }

        Class<?> cursor = repositoryClass;
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Set.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchFieldException(repositoryClass.getName() + ".sources");
    }

    private static Object createRepositorySource(ClassLoader loader, Object packType)
        throws ReflectiveOperationException {
        Class<?> repositorySourceClass = Class.forName(
            "net.minecraft.server.packs.repository.RepositorySource", false, loader);
        return Proxy.newProxyInstance(
            loader,
            new Class<?>[] { repositorySourceClass },
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "InterMedRepositorySource[" + packType + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    };
                }
                if (args != null
                    && args.length == 1
                    && args[0] instanceof Consumer<?> consumer
                    && (method.getName().equals("a")
                        || method.getName().equals("loadPacks")
                        || method.getName().equals("m_7686_"))) {
                    addPacks(loader, packType, consumer);
                }
                return null;
            }
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addPacks(ClassLoader loader, Object packType, Consumer<?> rawAcceptor) {
        List<File> resourceJars = collectResourceJars(loader);
        if (resourceJars.isEmpty()) {
            return;
        }

        Consumer acceptor = (Consumer) rawAcceptor;
        int accepted = 0;
        for (File jar : resourceJars) {
            try {
                Object pack = createPack(loader, packType, jar);
                if (pack != null) {
                    acceptor.accept(pack);
                    accepted++;
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                System.err.println("[ResourceBridge] Failed to create pack for "
                    + jar.getName() + ": " + describeThrowable(e));
            }
        }

        if (accepted > 0) {
            System.out.printf("[ResourceBridge] Mounted %d InterMed resource pack(s) for %s.%n",
                accepted, packType);
        }
    }

    private static Object createPack(ClassLoader loader, Object packType, File jar)
        throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component", false, loader);
        Class<?> packClass = Class.forName("net.minecraft.server.packs.repository.Pack", false, loader);
        Class<?> supplierClass = Class.forName("net.minecraft.server.packs.repository.Pack$ResourcesSupplier", false, loader);
        Class<?> positionClass = Class.forName("net.minecraft.server.packs.repository.Pack$Position", false, loader);
        Class<?> sourceClass = Class.forName("net.minecraft.server.packs.repository.PackSource", false, loader);
        Class<?> filePackResourcesClass = Class.forName("net.minecraft.server.packs.FilePackResources", false, loader);

        String id = "intermed_" + sanitize(String.valueOf(packType)) + "_" + sanitize(jar.getName());
        Object title = componentClass.getMethod("m_237113_", String.class)
            .invoke(null, "InterMed: " + jar.getName());
        Object topPosition = enumConstant(positionClass, "TOP");
        Object packSource = resolvePackSource(sourceClass);

        Constructor<?> filePackResourcesConstructor =
            filePackResourcesClass.getConstructor(String.class, File.class, boolean.class);
        Object supplier = Proxy.newProxyInstance(
            loader,
            new Class<?>[] { supplierClass },
            (proxy, method, args) -> filePackResourcesConstructor.newInstance(id, jar, false)
        );

        Method create = findPackFactoryMethod(
            packClass,
            componentClass,
            supplierClass,
            packType.getClass(),
            positionClass,
            sourceClass
        );
        return create.invoke(null, id, title, true, supplier, packType, topPosition, packSource);
    }

    private static Method findPackFactoryMethod(Class<?> packClass,
                                                Class<?> componentClass,
                                                Class<?> supplierClass,
                                                Class<?> packTypeClass,
                                                Class<?> positionClass,
                                                Class<?> sourceClass) throws NoSuchMethodException {
        for (Method method : packClass.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!packClass.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 7) {
                continue;
            }
            if (params[0] == String.class
                && params[1] == componentClass
                && params[2] == boolean.class
                && params[3] == supplierClass
                && params[4] == packTypeClass
                && params[5] == positionClass
                && params[6] == sourceClass) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(packClass.getName() + " static Pack factory(String, Component, boolean, ResourcesSupplier, PackType, Position, PackSource)");
    }

    private static Object resolvePackSource(Class<?> sourceClass) throws ReflectiveOperationException {
        for (String fieldName : List.of("c", "BUILT_IN", "b", "DEFAULT", "f_10528_")) {
            try {
                Field field = sourceClass.getField(fieldName);
                if (sourceClass.isAssignableFrom(field.getType())) {
                    return field.get(null);
                }
            } catch (NoSuchFieldException ignored) {
                // Try the next mapped name.
            }
        }
        for (Field field : sourceClass.getFields()) {
            if (sourceClass.isAssignableFrom(field.getType())) {
                String name = field.getName();
                if ("BUILT_IN".equals(name) || "DEFAULT".equals(name) || "c".equals(name) || "b".equals(name)) {
                    return field.get(null);
                }
            }
        }
        throw new NoSuchFieldException(sourceClass.getName() + " built-in/default source");
    }

    private static List<File> collectResourceJars(ClassLoader loader) {
        for (ClassLoader candidate : candidateLoaders(loader)) {
            try {
                Class<?> injector = Class.forName("org.intermed.core.bridge.assets.AssetInjector", true, candidate);
                Object value = injector.getMethod("collectResourceJars").invoke(null);
                if (value instanceof List<?> list) {
                    return list.stream()
                        .filter(File.class::isInstance)
                        .map(File.class::cast)
                        .toList();
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                if (Boolean.getBoolean("intermed.forge.pack.bridge.debug")) {
                    System.err.println("[ResourceBridge] AssetInjector lookup failed via "
                        + describeLoader(candidate) + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        System.err.println("[ResourceBridge] Unable to collect resource jars: AssetInjector is not visible.");
        return List.of();
    }

    private static List<ClassLoader> candidateLoaders(ClassLoader primary) {
        return java.util.stream.Stream.of(
                primary,
                Thread.currentThread().getContextClassLoader(),
                ForgePackRepositoryBridge.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
            )
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    private static String describeLoader(ClassLoader loader) {
        return loader == null ? "<bootstrap>" : loader.getClass().getName();
    }

    private static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Object enumConstant(Class<?> enumType, String name) {
        for (Object constant : enumType.getEnumConstants()) {
            if (name.equals(String.valueOf(constant))) {
                return constant;
            }
        }
        throw new IllegalArgumentException("No enum constant " + enumType.getName() + "." + name);
    }

    private static ClassLoader chooseLoader(ClassLoader eventLoader) {
        if (eventLoader != null) {
            return eventLoader;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClassLoader.getSystemClassLoader();
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT)
            .replace(".jar", "")
            .replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String describeThrowable(Throwable throwable) {
        Throwable root = throwable;
        while (root instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            root = invocation.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + ": " + (message == null ? "<no message>" : message);
    }
}
