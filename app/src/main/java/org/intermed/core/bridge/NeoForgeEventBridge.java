package org.intermed.core.bridge;

import org.intermed.core.bridge.assets.AssetInjector;
import org.intermed.core.classloading.TcclInterceptor;
import org.intermed.core.registry.VirtualRegistryService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runtime-registered NeoForge bridge.
 *
 * <p>The bridge intentionally avoids direct linker dependency on
 * {@code net.neoforged.*}, but still attaches real NeoForge mod-bus listeners
 * reflectively as soon as the runtime exposes its event bus.
 *
 * <p>ТЗ 3.2.2 — Registry Virtualization for NeoForge ecosystems.
 */
public class NeoForgeEventBridge {

    private static final String NEOFORGE_CONTEXT_CLASS = "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext";
    private static final String NEOFORGE_MOD_LOADING_CONTEXT_CLASS = "net.neoforged.fml.ModLoadingContext";
    private static final String NEOFORGE_PRIORITY_CLASS = "net.neoforged.bus.api.EventPriority";
    private static final String REGISTER_EVENT_CLASS = "net.neoforged.neoforge.registries.RegisterEvent";
    private static final String ADD_PACK_FINDERS_EVENT_CLASS = "net.neoforged.neoforge.event.AddPackFindersEvent";

    private static final AtomicBoolean REGISTRATION_PROBE_RUNNING = new AtomicBoolean(false);
    private static volatile boolean listenersRegistered = false;

    public static void scheduleRegistrationProbe() {
        if (listenersRegistered || !REGISTRATION_PROBE_RUNNING.compareAndSet(false, true)) {
            return;
        }

        Thread probe = TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-neoforge-bridge-probe");
            t.setDaemon(true);
            return t;
        }).newThread(() -> {
            try {
                for (int attempt = 0; attempt < 200 && !listenersRegistered; attempt++) {
                    if (registerIfAvailable()) {
                        return;
                    }
                    Thread.sleep(attempt < 20 ? 25L : 50L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                if (!listenersRegistered) {
                    REGISTRATION_PROBE_RUNNING.set(false);
                }
            }
        });
        probe.start();
    }

    public static boolean registerIfAvailable() {
        if (listenersRegistered) {
            return true;
        }

        synchronized (NeoForgeEventBridge.class) {
            if (listenersRegistered) {
                return true;
            }

            try {
                Object eventBus = resolveModEventBus();
                if (eventBus == null) {
                    return false;
                }

                boolean registerHooked = registerListener(eventBus, REGISTER_EVENT_CLASS, NeoForgeEventBridge::onRegister);
                if (!registerHooked) {
                    return false;
                }

                registerListener(eventBus, ADD_PACK_FINDERS_EVENT_CLASS, NeoForgeEventBridge::onAddPackFinders);

                listenersRegistered = true;
                REGISTRATION_PROBE_RUNNING.set(true);
                System.out.println("[NeoForgeBridge] Runtime listeners registered on NeoForge mod bus.");
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onRegister(Object event) {
        String regName = registryName(event);
        if (regName == null || regName.isBlank()) {
            return;
        }

        // ── Core ──────────────────────────────────────────────────────────────
        if (regName.equals("minecraft:block")) {
            injectAll("minecraft:block", "Blocks", RegistryCache.BLOCKS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "BLOCKS"));
        }
        if (regName.equals("minecraft:item")) {
            injectAll("minecraft:item", "Items", RegistryCache.ITEMS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "ITEMS"));
        }
        if (regName.equals("minecraft:entity_type")) {
            injectAll("minecraft:entity_type", "Entities", RegistryCache.ENTITIES,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "ENTITY_TYPES"));
        }
        if (regName.equals("minecraft:sound_event")) {
            injectAll("minecraft:sound_event", "Sounds", RegistryCache.SOUNDS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "SOUND_EVENTS"));
        }

        // ── Extended ──────────────────────────────────────────────────────────
        if (regName.equals("minecraft:enchantment")) {
            injectAll("minecraft:enchantment", "Enchantments", RegistryCache.ENCHANTMENTS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "ENCHANTMENTS"));
        }
        if (regName.equals("minecraft:mob_effect")) {
            injectAll("minecraft:mob_effect", "MobEffects", RegistryCache.MOB_EFFECTS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "MOB_EFFECTS"));
        }
        if (regName.equals("minecraft:potion")) {
            injectAll("minecraft:potion", "Potions", RegistryCache.POTIONS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "POTIONS"));
        }
        if (regName.equals("neoforge:fluid_type")) {
            injectAll("neoforge:fluid_type", "FluidTypes", RegistryCache.FLUID_TYPES,
                registryField("net.neoforged.neoforge.registries.NeoForgeRegistries", "FLUID_TYPES"));
        }
        if (regName.equals("minecraft:creative_mode_tab")) {
            injectAll("minecraft:creative_mode_tab", "CreativeModeTabs", RegistryCache.CREATIVE_MODE_TABS,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "CREATIVE_MODE_TABS"));
        }
        if (regName.equals("minecraft:recipe_type")) {
            injectAll("minecraft:recipe_type", "RecipeTypes", RegistryCache.RECIPE_TYPES,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "RECIPE_TYPES"));
        }
        if (regName.equals("minecraft:particle_type")) {
            injectAll("minecraft:particle_type", "ParticleTypes", RegistryCache.PARTICLE_TYPES,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "PARTICLE_TYPES"));
        }
        if (regName.equals("minecraft:block_entity_type")) {
            injectAll("minecraft:block_entity_type", "BlockEntityTypes", RegistryCache.BLOCK_ENTITY_TYPES,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "BLOCK_ENTITY_TYPES"));
        }
        if (regName.equals("minecraft:feature")) {
            injectAll("minecraft:feature", "Features", RegistryCache.FEATURES,
                registryField("net.neoforged.neoforge.registries.ForgeRegistries", "FEATURES"));
        }
    }

    /**
     * NeoForge-specific: mounts Fabric resource-pack JARs into the NeoForge
     * pack repository so that textures, models, and lang files from Fabric mods
     * are visible to the game's resource system.
     */
    public static void onAddPackFinders(Object event) {
        try {
            List<java.io.File> resourceJars = AssetInjector.collectResourceJars();
            for (java.io.File jar : resourceJars) {
                Path path = jar.toPath().toAbsolutePath().normalize();
                try {
                    FileSystems.newFileSystem(path, Map.of());
                } catch (java.nio.file.FileSystemAlreadyExistsException ignored) {
                    // Already mounted.
                } catch (Exception mountError) {
                    System.err.println("[NeoForgeBridge] Failed to mount "
                        + path.getFileName() + ": " + mountError.getMessage());
                    continue;
                }
                System.out.println("\033[1;32m[NeoForgeBridge] Resource pack mounted: "
                    + path.getFileName() + "\033[0m");
            }
        } catch (Exception e) {
            System.err.println("[NeoForgeBridge] Pack finder bridge failed: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void injectAll(String registryScope,
                                  String label,
                                  List<RegistryCache.PendingEntry> pending,
                                  Object registry) {
        if (pending.isEmpty() || registry == null) return;
        System.out.printf("\033[1;35m[NeoForgeBridge] Injecting %d %s\033[0m%n", pending.size(), label);
        for (RegistryCache.PendingEntry e : pending) {
            try {
                VirtualRegistryService.registerVirtualized(e.modId, registryScope, e.id.toString(), -1, e.entry);
                invokeRegister(registry, e.id, e.entry);
            } catch (Exception ex) {
                System.err.printf("[NeoForgeBridge] Failed to register %s (%s): %s%n",
                    e.id, label, ex.getMessage());
            }
        }
    }

    private static String registryName(Object event) {
        if (event == null) {
            return null;
        }
        Object registryKey = invokeNoArg(event, "getRegistryKey");
        Object location = invokeNoArg(registryKey, "location");
        return location != null ? String.valueOf(location) : null;
    }

    private static Object registryField(String ownerClass, String fieldName) {
        try {
            Class<?> type = loadClass(ownerClass);
            Field field = type.getField(fieldName);
            return field.get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void invokeRegister(Object registry, Object id, Object entry) throws ReflectiveOperationException {
        Method method = findRegisterMethod(registry.getClass());
        method.invoke(registry, id, entry);
    }

    private static Method findRegisterMethod(Class<?> registryType) throws NoSuchMethodException {
        for (Method method : registryType.getMethods()) {
            if (method.getName().equals("register") && method.getParameterCount() == 2) {
                return method;
            }
        }
        throw new NoSuchMethodException("register(ResourceLocation, Object)");
    }

    static void resetForTests() {
        listenersRegistered = false;
        REGISTRATION_PROBE_RUNNING.set(false);
    }

    private static Object resolveModEventBus() throws ReflectiveOperationException {
        Object javaFmlContext = resolveStaticContext(NEOFORGE_CONTEXT_CLASS, "get");
        Object eventBus = invokeNoArg(javaFmlContext, "getModEventBus");
        if (eventBus != null) {
            return eventBus;
        }

        Object modLoadingContext = resolveStaticContext(NEOFORGE_MOD_LOADING_CONTEXT_CLASS, "get");
        Object activeContainer = invokeNoArg(modLoadingContext, "getActiveContainer");
        eventBus = invokeNoArg(activeContainer, "getEventBus");
        if (eventBus != null) {
            return eventBus;
        }

        return null;
    }

    private static Object resolveStaticContext(String className, String factoryMethod) throws ReflectiveOperationException {
        try {
            Class<?> type = loadClass(className);
            Method factory = type.getMethod(factoryMethod);
            return factory.invoke(null);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean registerListener(Object eventBus,
                                            String eventClassName,
                                            Consumer<Object> listener) throws ReflectiveOperationException {
        Class<?> eventType;
        try {
            eventType = loadClass(eventClassName);
        } catch (ClassNotFoundException ignored) {
            return false;
        }

        Class<?> priorityType = loadClass(NEOFORGE_PRIORITY_CLASS);
        Object normalPriority = Enum.valueOf((Class<? extends Enum>) priorityType.asSubclass(Enum.class), "NORMAL");
        Method addListener = eventBus.getClass().getMethod(
            "addListener",
            priorityType,
            boolean.class,
            Class.class,
            Consumer.class
        );
        addListener.invoke(eventBus, normalPriority, false, eventType, listener);
        return true;
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(className, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // Fall back to the InterMed class loader.
            }
        }
        return Class.forName(className, false, NeoForgeEventBridge.class.getClassLoader());
    }
}
