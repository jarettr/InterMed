package org.intermed.core.mixin;

import org.intermed.mixin.InterMedMixinBootstrap;
import org.intermed.mixin.service.InterMedGlobalPropertyService;
import org.intermed.mixin.service.InterMedMixinService;
import org.spongepowered.asm.launch.GlobalProperties;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.IMixinServiceBootstrap;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.service.ServiceNotAvailableError;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Ensures the InterMed Mixin fork is the active runtime integration path.
 *
 * <p>Production readiness here means verifying the <em>actual</em> selected
 * {@link IMixinService}, not merely checking that the provider class is present
 * on the classpath.
 */
public final class MixinTransmogrifier {

    private static final String MIXIN_RUNTIME_CLASS = "org.spongepowered.asm.service.MixinService";
    private static final String MIXIN_SERVICE_RESOURCE = "META-INF/services/org.spongepowered.asm.service.IMixinService";
    private static final String MIXIN_BOOTSTRAP_RESOURCE =
        "META-INF/services/org.spongepowered.asm.service.IMixinServiceBootstrap";
    private static final String GLOBAL_PROPERTY_RESOURCE =
        "META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService";
    private static final String BOOTSTRAP_PROVIDER =
        "org.intermed.mixin.service.InterMedMixinServiceBootstrap";
    private static final String GLOBAL_PROPERTY_PROVIDER =
        "org.intermed.mixin.service.InterMedGlobalPropertyService";
    private static final String MIXIN_SERVICE_INSTANCE_FIELD = "instance";
    private static final String MIXIN_SERVICE_SELECTED_FIELD = "service";
    private static final String MIXIN_PROPERTY_SELECTED_FIELD = "propertyService";
    private static final String MIXIN_BOOTED_SERVICES_FIELD = "bootedServices";
    private static final String GLOBAL_PROPERTIES_SERVICE_FIELD = "service";

    public enum ActivationMode {
        INACTIVE,
        CANONICAL_SERVICE,
        REFLECTION_HIJACK,
        NO_MIXIN_RUNTIME,
        FAILED
    }

    private static volatile boolean active = false;
    private static volatile ActivationMode activationMode = ActivationMode.INACTIVE;
    private static volatile String diagnostics = "inactive";
    private static volatile List<String> serviceDescriptors = List.of();
    private static volatile List<String> bootstrapDescriptors = List.of();
    private static volatile List<String> globalPropertyDescriptors = List.of();
    private static volatile String selectedServiceClass = "";
    private static volatile String selectedGlobalPropertyClass = "";

    private MixinTransmogrifier() {}

    public static synchronized void bootstrapTransmogrification() {
        if (active) {
            return;
        }

        if (!isMixinRuntimePresent()) {
            activationMode = ActivationMode.NO_MIXIN_RUNTIME;
            diagnostics = "Mixin runtime is not present on the active classpath";
            System.out.println("[Transmogrifier] " + diagnostics + ".");
            return;
        }

        ServiceDescriptorProbe probe = readServiceDescriptors();
        serviceDescriptors = probe.serviceProviders();
        bootstrapDescriptors = probe.bootstrapProviders();
        globalPropertyDescriptors = probe.globalPropertyProviders();

        if (!serviceDescriptors.contains(InterMedMixinService.class.getName())) {
            failActivation("IMixinService descriptor does not declare " + InterMedMixinService.class.getName());
        }
        if (!bootstrapDescriptors.contains(BOOTSTRAP_PROVIDER)) {
            failActivation("IMixinServiceBootstrap descriptor does not declare " + BOOTSTRAP_PROVIDER);
        }
        if (!globalPropertyDescriptors.contains(GLOBAL_PROPERTY_PROVIDER)) {
            failActivation("IGlobalPropertyService descriptor does not declare " + GLOBAL_PROPERTY_PROVIDER);
        }

        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader canonicalLoader = InterMedMixinService.class.getClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(canonicalLoader);
            invokeCanonicalBootstrap(canonicalLoader);
            RuntimeSelection preBootstrapSelection = currentRuntimeSelection();
            ReflectionHijackResult preBootstrapHijack =
                ensureCanonicalRuntimeSelection(canonicalLoader, preBootstrapSelection, false);
            MixinBootstrap.init();
            RuntimeSelection postBootstrapSelection = currentRuntimeSelection();
            ReflectionHijackResult postBootstrapHijack =
                ensureCanonicalRuntimeSelection(canonicalLoader, postBootstrapSelection, true);
            RuntimeSelection finalSelection = currentRuntimeSelection();
            selectedServiceClass = finalSelection.serviceClassName();
            selectedGlobalPropertyClass = finalSelection.propertyServiceClassName();

            if (!isCanonicalGlobalPropertyService(finalSelection.globalPropertyService())) {
                failActivation("Mixin runtime selected " + selectedGlobalPropertyClass
                    + " instead of canonical " + InterMedGlobalPropertyService.class.getName()
                    + " for IGlobalPropertyService");
            }
            if (!isCanonicalMixinService(finalSelection.mixinService())) {
                failActivation("Mixin runtime selected " + selectedServiceClass
                    + " instead of canonical " + InterMedMixinService.class.getName());
            }

            InterMedMixinBootstrap.init();
            active = true;
            activationMode = preBootstrapHijack.applied() || postBootstrapHijack.applied()
                ? ActivationMode.REFLECTION_HIJACK
                : ActivationMode.CANONICAL_SERVICE;
            diagnostics = "canonical=" + selectedServiceClass
                + ", globalProperties=" + selectedGlobalPropertyClass
                + ", preSelection=" + preBootstrapSelection.summary()
                + ", preHijack=" + preBootstrapHijack.summary()
                + ", postSelection=" + postBootstrapSelection.summary()
                + ", recovery=" + postBootstrapHijack.summary()
                + ", bootstrapInvoked=" + InterMedMixinBootstrap.wasServiceBootstrapInvoked()
                + ", bootstrap=" + InterMedMixinBootstrap.diagnostics();
            System.out.println("[Transmogrifier] Canonical InterMed mixin service active: " + diagnostics);
        } catch (ServiceNotAvailableError serviceNotAvailableError) {
            if (shouldDeferForMissingHostService(serviceNotAvailableError)) {
                activationMode = ActivationMode.INACTIVE;
                diagnostics = "Deferred: " + serviceNotAvailableError.getMessage();
                System.out.println("[Transmogrifier] " + diagnostics);
                return;
            }
            throw serviceNotAvailableError;
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            failActivation("Failed to activate canonical Mixin service: "
                + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static ActivationMode getActivationMode() {
        return activationMode;
    }

    public static String getDiagnostics() {
        return diagnostics;
    }

    public static List<String> getServiceDescriptors() {
        return serviceDescriptors;
    }

    public static List<String> getBootstrapDescriptors() {
        return bootstrapDescriptors;
    }

    public static List<String> getGlobalPropertyDescriptors() {
        return globalPropertyDescriptors;
    }

    public static String getSelectedServiceClass() {
        return selectedServiceClass;
    }

    public static String getSelectedGlobalPropertyClass() {
        return selectedGlobalPropertyClass;
    }

    public static void resetForTests() {
        active = false;
        activationMode = ActivationMode.INACTIVE;
        diagnostics = "inactive";
        serviceDescriptors = List.of();
        bootstrapDescriptors = List.of();
        globalPropertyDescriptors = List.of();
        selectedServiceClass = "";
        selectedGlobalPropertyClass = "";
        resetRuntimeSelectionForTests();
    }

    private static boolean isMixinRuntimePresent() {
        try {
            Class.forName(
                MIXIN_RUNTIME_CLASS,
                false,
                MixinTransmogrifier.class.getClassLoader()
            );
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean shouldDeferForMissingHostService(ServiceNotAvailableError error) {
        String message = error.getMessage();
        return message != null && message.contains("No mixin host service is available");
    }

    private static void failActivation(String message) {
        active = false;
        activationMode = ActivationMode.FAILED;
        diagnostics = message;
        System.err.println("[Transmogrifier] " + message);
        throw new IllegalStateException(message);
    }

    private static void invokeCanonicalBootstrap(ClassLoader canonicalLoader) {
        boolean invoked = false;
        for (IMixinServiceBootstrap bootstrap : ServiceLoader.load(IMixinServiceBootstrap.class, canonicalLoader)) {
            String bootstrapClass = bootstrap.getClass().getName();
            boolean canonicalProvider = BOOTSTRAP_PROVIDER.equals(bootstrapClass);
            boolean canonicalService = InterMedMixinService.class.getName().equals(bootstrap.getServiceClassName());
            if (!canonicalProvider && !canonicalService) {
                continue;
            }
            bootstrap.bootstrap();
            invoked = true;
            break;
        }

        if (!invoked) {
            try {
                IMixinServiceBootstrap bootstrap = (IMixinServiceBootstrap) Class
                    .forName(BOOTSTRAP_PROVIDER, true, canonicalLoader)
                    .getDeclaredConstructor()
                    .newInstance();
                bootstrap.bootstrap();
            } catch (ReflectiveOperationException reflectionException) {
                failActivation("Failed to invoke canonical Mixin bootstrap: "
                    + reflectionException.getClass().getSimpleName() + ": "
                    + reflectionException.getMessage());
            }
        }
    }

    static void installRuntimeSelectionForTests(IMixinService service, IGlobalPropertyService propertyService) {
        overrideRuntimeSelection(service, propertyService, false);
    }

    private static RuntimeSelection currentRuntimeSelection() {
        IMixinService selectedService = MixinService.getService();
        IGlobalPropertyService propertyService = MixinService.getGlobalPropertyService();
        return new RuntimeSelection(selectedService, propertyService);
    }

    private static ReflectionHijackResult ensureCanonicalRuntimeSelection(ClassLoader canonicalLoader,
                                                                         RuntimeSelection selection,
                                                                         boolean afterBootstrap) {
        if (isCanonicalMixinService(selection.mixinService())
            && isCanonicalGlobalPropertyService(selection.globalPropertyService())) {
            return ReflectionHijackResult.none(selection);
        }

        IMixinService canonicalService = instantiateCanonicalMixinService(canonicalLoader);
        IGlobalPropertyService canonicalPropertyService = resolveCanonicalGlobalPropertyService(canonicalLoader);
        ReflectionHijackResult hijackResult = overrideRuntimeSelection(
            canonicalService,
            canonicalPropertyService,
            afterBootstrap
        );
        if (afterBootstrap && hijackResult.applied()) {
            warmRecoveredRuntime(canonicalService);
        }
        return hijackResult;
    }

    private static ReflectionHijackResult overrideRuntimeSelection(IMixinService service,
                                                                   IGlobalPropertyService propertyService,
                                                                   boolean includeLifecycleWarmup) {
        try {
            MixinService.boot();
            Object singleton = readStaticField(MixinService.class,
                MIXIN_SERVICE_INSTANCE_FIELD, "INSTANCE", "sInstance");
            if (singleton == null) {
                failActivation("MixinService singleton did not materialise during reflective hijack");
            }

            Object previousService = readField(singleton,
                MIXIN_SERVICE_SELECTED_FIELD, "currentService", "mixinService");
            Object previousPropertyService = readField(singleton,
                MIXIN_PROPERTY_SELECTED_FIELD, "globalPropertyService", "propertyService");
            boolean serviceInjected = !isCanonicalMixinService(previousService) && service != null;
            boolean propertyInjected = !isCanonicalGlobalPropertyService(previousPropertyService)
                && propertyService != null;

            if (serviceInjected) {
                writeField(singleton, MIXIN_SERVICE_SELECTED_FIELD, service);
                @SuppressWarnings("unchecked")
                java.util.Set<String> bootedServices =
                    (java.util.Set<String>) readField(singleton,
                        MIXIN_BOOTED_SERVICES_FIELD, "initializedServices", "loadedServices");
                if (bootedServices != null) {
                    bootedServices.add(InterMedMixinService.class.getName());
                }
            }
            if (propertyInjected) {
                writeField(singleton, MIXIN_PROPERTY_SELECTED_FIELD, propertyService);
            }
            if (propertyService != null && (propertyInjected || includeLifecycleWarmup)) {
                writeStaticField(GlobalProperties.class, GLOBAL_PROPERTIES_SERVICE_FIELD, propertyService);
            }

            return new ReflectionHijackResult(
                serviceInjected,
                propertyInjected,
                classNameOf(previousService),
                classNameOf(previousPropertyService)
            );
        } catch (ReflectiveOperationException reflectionException) {
            failActivation("Failed reflective Mixin hijack: "
                + reflectionException.getClass().getSimpleName() + ": "
                + reflectionException.getMessage());
            throw new IllegalStateException(reflectionException);
        }
    }

    private static IMixinService instantiateCanonicalMixinService(ClassLoader canonicalLoader) {
        try {
            return (IMixinService) Class
                .forName(InterMedMixinService.class.getName(), true, canonicalLoader)
                .getDeclaredConstructor()
                .newInstance();
        } catch (ReflectiveOperationException reflectionException) {
            failActivation("Failed to instantiate canonical Mixin service: "
                + reflectionException.getClass().getSimpleName() + ": "
                + reflectionException.getMessage());
            throw new IllegalStateException(reflectionException);
        }
    }

    private static IGlobalPropertyService resolveCanonicalGlobalPropertyService(ClassLoader canonicalLoader) {
        ServiceLoader<IGlobalPropertyService> loader = ServiceLoader.load(IGlobalPropertyService.class, canonicalLoader);
        java.util.Optional<ServiceLoader.Provider<IGlobalPropertyService>> provider = loader.stream()
            .filter(candidate -> GLOBAL_PROPERTY_PROVIDER.equals(candidate.type().getName()))
            .findFirst();
        if (provider.isPresent()) {
            return provider.get().get();
        }
        try {
            return (IGlobalPropertyService) Class
                .forName(GLOBAL_PROPERTY_PROVIDER, true, canonicalLoader)
                .getDeclaredConstructor()
                .newInstance();
        } catch (ReflectiveOperationException reflectionException) {
            failActivation("Failed to instantiate canonical global property service: "
                + reflectionException.getClass().getSimpleName() + ": "
                + reflectionException.getMessage());
            throw new IllegalStateException(reflectionException);
        }
    }

    private static void warmRecoveredRuntime(IMixinService canonicalService) {
        try {
            InterMedPlatformAgent.prepareFromLifecycle();
            canonicalService.prepare();
            canonicalService.beginPhase();
            canonicalService.init();
        } catch (Throwable throwable) {
            failActivation("Failed to warm recovered canonical Mixin runtime: "
                + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static boolean isCanonicalMixinService(Object service) {
        return service != null && InterMedMixinService.class.getName().equals(service.getClass().getName());
    }

    private static boolean isCanonicalGlobalPropertyService(Object propertyService) {
        return propertyService != null
            && InterMedGlobalPropertyService.class.getName().equals(propertyService.getClass().getName());
    }

    private static String classNameOf(Object value) {
        return value == null ? "<none>" : value.getClass().getName();
    }

    private static Object readStaticField(Class<?> owner, String... candidateNames) throws ReflectiveOperationException {
        Field field = findField(owner, candidateNames);
        return field.get(null);
    }

    private static void writeStaticField(Class<?> owner, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(owner, fieldName);
        field.set(null, value);
    }

    private static Object readField(Object target, String... candidateNames) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), candidateNames);
        return field.get(target);
    }

    private static void writeField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.set(target, value);
    }

    /** Package-private to allow unit testing of the resilience logic. */
    static Field findFieldForTests(Class<?> startClass, String... candidateNames) throws NoSuchFieldException {
        return findField(startClass, candidateNames);
    }

    /**
     * Finds an accessible field by walking the full class hierarchy and trying each
     * candidate name in order.  Throws {@link NoSuchFieldException} with a descriptive
     * message listing all tried names and the fields actually declared on the class if
     * none of the candidates is found.
     */
    private static Field findField(Class<?> startClass, String... candidateNames) throws NoSuchFieldException {
        for (String name : candidateNames) {
            Class<?> cursor = startClass;
            while (cursor != null && cursor != Object.class) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
        }
        // Build a diagnostic message listing what was actually declared.
        java.util.StringJoiner declared = new java.util.StringJoiner(", ");
        Class<?> cursor = startClass;
        while (cursor != null && cursor != Object.class) {
            for (Field f : cursor.getDeclaredFields()) declared.add(cursor.getSimpleName() + "#" + f.getName());
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchFieldException(
            "Could not find field(s) " + java.util.Arrays.toString(candidateNames)
            + " in " + startClass.getName()
            + ". Declared fields: [" + declared + "]");
    }

    private static ServiceDescriptorProbe readServiceDescriptors() {
        return new ServiceDescriptorProbe(
            readProviders(MIXIN_SERVICE_RESOURCE),
            readProviders(MIXIN_BOOTSTRAP_RESOURCE),
            readProviders(GLOBAL_PROPERTY_RESOURCE)
        );
    }

    private static List<String> readProviders(String resourceName) {
        List<String> providers = new ArrayList<>();
        try {
            Enumeration<java.net.URL> resources = InterMedMixinService.class.getClassLoader().getResources(resourceName);
            while (resources.hasMoreElements()) {
                java.net.URL resource = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String normalized = normalizeProviderLine(line);
                        if (normalized != null && !providers.contains(normalized)) {
                            providers.add(normalized);
                        }
                    }
                }
            }
        } catch (Exception exception) {
            System.err.println("[Transmogrifier] Failed to read " + resourceName + ": " + exception.getMessage());
        }
        return List.copyOf(providers);
    }

    private static String normalizeProviderLine(String line) {
        if (line == null) {
            return null;
        }
        int commentIndex = line.indexOf('#');
        String cleaned = (commentIndex >= 0 ? line.substring(0, commentIndex) : line).trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static void resetRuntimeSelectionForTests() {
        try {
            writeStaticField(MixinService.class, MIXIN_SERVICE_INSTANCE_FIELD, null);
            // Also try alternate names used in different Mixin upstream versions.
        } catch (ReflectiveOperationException ignored) {
            // Ignore — the runtime may not have been initialised yet.
        }
        try {
            writeStaticField(GlobalProperties.class, GLOBAL_PROPERTIES_SERVICE_FIELD, null);
        } catch (ReflectiveOperationException ignored) {
            // Ignore — the runtime may not have been initialised yet.
        }
        try {
            GlobalProperties.put(GlobalProperties.Keys.INIT, null);
            GlobalProperties.put(GlobalProperties.Keys.PLATFORM_MANAGER, null);
            GlobalProperties.put(GlobalProperties.Keys.CONFIGS, null);
            GlobalProperties.put(GlobalProperties.Keys.AGENTS, null);
        } catch (Throwable ignored) {
            // Ignore — these caches are best-effort test hygiene only.
        }
        try {
            writeStaticField(MixinBootstrap.class, "initialised", false);
            writeStaticField(MixinBootstrap.class, "initState", true);
            writeStaticField(MixinBootstrap.class, "logger", null);
            writeStaticField(MixinBootstrap.class, "platform", null);
        } catch (ReflectiveOperationException ignored) {
            // Ignore — if fields change upstream we still want tests to proceed.
        }
    }

    private record ServiceDescriptorProbe(List<String> serviceProviders,
                                          List<String> bootstrapProviders,
                                          List<String> globalPropertyProviders) {}

    private record RuntimeSelection(IMixinService mixinService,
                                    IGlobalPropertyService globalPropertyService) {

        private String serviceClassName() {
            return classNameOf(mixinService);
        }

        private String propertyServiceClassName() {
            return classNameOf(globalPropertyService);
        }

        private String summary() {
            return serviceClassName() + "/" + propertyServiceClassName();
        }
    }

    private record ReflectionHijackResult(boolean serviceInjected,
                                          boolean propertyInjected,
                                          String previousServiceClass,
                                          String previousPropertyClass) {

        private static ReflectionHijackResult none(RuntimeSelection selection) {
            return new ReflectionHijackResult(
                false,
                false,
                selection.serviceClassName(),
                selection.propertyServiceClassName()
            );
        }

        private boolean applied() {
            return serviceInjected || propertyInjected;
        }

        private String summary() {
            if (!applied()) {
                return "none(" + previousServiceClass + "/" + previousPropertyClass + ")";
            }
            return "service=" + serviceInjected + "[" + previousServiceClass + "]"
                + ", property=" + propertyInjected + "[" + previousPropertyClass + "]";
        }
    }
}
