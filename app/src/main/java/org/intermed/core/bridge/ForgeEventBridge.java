package org.intermed.core.bridge;

import org.intermed.core.bridge.assets.AssetInjector;
import org.intermed.core.bridge.forge.ForgePackRepositoryBridge;
import org.intermed.core.classloading.TcclInterceptor;
import org.intermed.core.registry.VirtualRegistryService;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runtime-registered Forge mod-bus bridge.
 *
 * <p>Unlike the game-bus bridge, this one targets the Forge mod bus so that
 * registry injection and resource-pack discovery happen at the same lifecycle
 * phase as native Forge mods. We intentionally register reflectively against
 * the runtime loader's event bus to avoid class-identity drift between the
 * javaagent/system loader and Forge's transformed classes.
 */
public class ForgeEventBridge {

    private static final String FORGE_CONTEXT_CLASS = "net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext";
    private static final String FORGE_MOD_LOADING_CONTEXT_CLASS = "net.minecraftforge.fml.ModLoadingContext";
    private static final String FORGE_PRIORITY_CLASS = "net.minecraftforge.eventbus.api.EventPriority";
    private static final String REGISTER_EVENT_CLASS = "net.minecraftforge.registries.RegisterEvent";
    private static final String ADD_PACK_FINDERS_EVENT_CLASS = "net.minecraftforge.event.AddPackFindersEvent";

    private static final AtomicBoolean REGISTRATION_PROBE_RUNNING = new AtomicBoolean(false);
    private static volatile boolean listenersRegistered = false;

    public static void scheduleRegistrationProbe() {
        if (listenersRegistered || !REGISTRATION_PROBE_RUNNING.compareAndSet(false, true)) {
            return;
        }

        Thread probe = TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-forge-mod-bridge-probe");
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

        synchronized (ForgeEventBridge.class) {
            if (listenersRegistered) {
                return true;
            }

            try {
                Object eventBus = resolveModEventBus();
                if (eventBus == null) {
                    return false;
                }

                boolean registerHooked = registerListener(eventBus, REGISTER_EVENT_CLASS, ForgeEventBridge::onRegister);
                if (!registerHooked) {
                    return false;
                }

                registerListener(eventBus, ADD_PACK_FINDERS_EVENT_CLASS, ForgeEventBridge::onPackFinder);

                listenersRegistered = true;
                REGISTRATION_PROBE_RUNNING.set(true);
                System.out.println("[ForgeBridge] Runtime listeners registered on Forge mod bus.");
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
                registryField("net.minecraftforge.registries.ForgeRegistries", "BLOCKS"));
        }
        if (regName.equals("minecraft:item")) {
            injectAll("minecraft:item", "Items", RegistryCache.ITEMS,
                registryField("net.minecraftforge.registries.ForgeRegistries", "ITEMS"));
        }
        if (regName.equals("minecraft:entity_type")) {
            injectAll("minecraft:entity_type", "Entities", RegistryCache.ENTITIES,
                registryField("net.minecraftforge.registries.ForgeRegistries", "ENTITY_TYPES"));
        }
        if (regName.equals("minecraft:sound_event")) {
            injectAll("minecraft:sound_event", "Sounds", RegistryCache.SOUNDS,
                registryField("net.minecraftforge.registries.ForgeRegistries", "SOUND_EVENTS"));
        }

        // ── Extended registry types ────────────────────────────────────────────
        if (regName.equals("minecraft:enchantment")) {
            injectAll("minecraft:enchantment", "Enchantments", RegistryCache.ENCHANTMENTS,
                registryField("net.minecraftforge.registries.ForgeRegistries", "ENCHANTMENTS"));
        }
        if (regName.equals("minecraft:mob_effect")) {
            injectAll("minecraft:mob_effect", "MobEffects", RegistryCache.MOB_EFFECTS,
                registryField("net.minecraftforge.registries.ForgeRegistries", "MOB_EFFECTS"));
        }
        if (regName.equals("minecraft:potion")) {
            injectAll("minecraft:potion", "Potions", RegistryCache.POTIONS,
                registryField("net.minecraftforge.registries.ForgeRegistries", "POTIONS"));
        }
        if (regName.equals("forge:fluid_type")) {
            injectAll("forge:fluid_type", "FluidTypes", RegistryCache.FLUID_TYPES,
                registryField("net.minecraftforge.registries.ForgeRegistries", "FLUID_TYPES"));
        }
        if (regName.equals("minecraft:creative_mode_tab")) {
            injectAll("minecraft:creative_mode_tab", "CreativeModeTabs", RegistryCache.CREATIVE_MODE_TABS,
                registryField("net.minecraftforge.registries.ForgeRegistries", "CREATIVE_MODE_TABS"));
        }
        if (regName.equals("minecraft:recipe_type")) {
            injectAll("minecraft:recipe_type", "RecipeTypes", RegistryCache.RECIPE_TYPES,
                registryField("net.minecraftforge.registries.ForgeRegistries", "RECIPE_TYPES"));
        }
        if (regName.equals("minecraft:particle_type")) {
            injectAll("minecraft:particle_type", "ParticleTypes", RegistryCache.PARTICLE_TYPES,
                registryField("net.minecraftforge.registries.ForgeRegistries", "PARTICLE_TYPES"));
        }
        if (regName.equals("minecraft:block_entity_type")) {
            injectAll("minecraft:block_entity_type", "BlockEntityTypes", RegistryCache.BLOCK_ENTITY_TYPES,
                registryField("net.minecraftforge.registries.ForgeRegistries", "BLOCK_ENTITY_TYPES"));
        }
        if (regName.equals("minecraft:feature")) {
            injectAll("minecraft:feature", "Features", RegistryCache.FEATURES,
                registryField("net.minecraftforge.registries.ForgeRegistries", "FEATURES"));
        }
    }

    public static void onPackFinder(Object event) {
        try {
            // Prefer the public Forge hook first so resource-pack registration
            // still works even if the early ByteBuddy bridge could not attach.
            ForgePackRepositoryBridge.install(event);

            List<java.io.File> resourceJars = AssetInjector.collectResourceJars();
            for (java.io.File jar : resourceJars) {
                Path path = jar.toPath().toAbsolutePath().normalize();
                try {
                    FileSystems.newFileSystem(path, Map.of());
                } catch (java.nio.file.FileSystemAlreadyExistsException ignored) {
                    // Already mounted for this JVM session.
                } catch (Exception mountError) {
                    System.err.println("[ResourceBridge] Failed to mount "
                        + path.getFileName() + ": " + mountError.getMessage());
                    continue;
                }
                System.out.println("\033[1;32m[ResourceBridge] Resource pack candidate ready: "
                    + path.getFileName() + "\033[0m");
            }
        } catch (Exception e) {
            System.err.println("[ResourceBridge] Pack finder bridge failed: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void injectAll(String registryScope,
                                  String label,
                                  List<RegistryCache.PendingEntry> pending,
                                  Object registry) {
        if (pending.isEmpty() || registry == null) return;
        System.out.printf("\033[1;35m[ForgeBridge] Injecting %d %s\033[0m%n", pending.size(), label);
        for (RegistryCache.PendingEntry e : pending) {
            try {
                VirtualRegistryService.registerVirtualized(e.modId, registryScope, e.id.toString(), -1, e.entry);
                invokeRegister(registry, e.id, e.entry);
            } catch (Exception ex) {
                System.err.printf("[ForgeBridge] Failed to register %s (%s): %s%n",
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
            return type.getField(fieldName).get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean registerListener(Object eventBus, String eventClassName, Consumer<Object> handler)
        throws ReflectiveOperationException {
        Method addListener = findAddListener(eventBus.getClass());
        if (addListener == null) {
            return false;
        }
        Class<?> eventClass = Class.forName(eventClassName, false, Thread.currentThread().getContextClassLoader());
        Object normal = loadClass(FORGE_PRIORITY_CLASS).getField("NORMAL").get(null);
        addListener.invoke(eventBus, normal, false, eventClass, (Consumer) handler);
        return true;
    }

    private static Method findAddListener(Class<?> eventBusType) {
        for (Method method : eventBusType.getMethods()) {
            if ("addListener".equals(method.getName())
                && method.getParameterCount() == 4
                && method.getParameterTypes()[1] == boolean.class
                && method.getParameterTypes()[2] == Class.class
                && Consumer.class.isAssignableFrom(method.getParameterTypes()[3])) {
                return method;
            }
        }
        return null;
    }

    private static Object resolveModEventBus() throws ReflectiveOperationException {
        Object javaFmlContext = resolveStaticContext(FORGE_CONTEXT_CLASS, "get");
        Object eventBus = invokeNoArg(javaFmlContext, "getModEventBus");
        if (eventBus != null) {
            return eventBus;
        }

        Object modLoadingContext = resolveStaticContext(FORGE_MOD_LOADING_CONTEXT_CLASS, "get");
        Object activeContainer = invokeNoArg(modLoadingContext, "getActiveContainer");
        if (activeContainer == null) {
            return null;
        }
        return invokeNoArg(activeContainer, "getEventBus");
    }

    private static Object resolveStaticContext(String className, String accessor) throws ReflectiveOperationException {
        Class<?> type = loadClass(className);
        Method method = type.getMethod(accessor);
        return method.invoke(null);
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ForgeEventBridge.class.getClassLoader();
        }
        return Class.forName(className, false, loader);
    }
}
