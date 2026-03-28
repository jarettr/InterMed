package org.intermed.core.bridge;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.registry.VirtualRegistryService;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Виртуальный реестр InterMed (Раздел 3.2.1 ТЗ).
 * Перехватывает мгновенную регистрацию Fabric и перенаправляет её в Forge RegisterEvent.
 */
public class InterMedRegistry {

    // Хранилище перехваченных объектов (Предметы, Блоки и т.д.)
    private static final Map<ResourceKey<?>, List<PendingRegistration>> QUEUE = new ConcurrentHashMap<>();

    // ТЗ 3.2.6: Кэширование MethodHandle для избежания накладных расходов рефлексии в рантайме
    private static MethodHandle cachedRegisterMethod;

    /**
     * Этот метод вызывается из Fabric-модов ВМЕСТО оригинального Registry.register.
     * Заменяется на лету через FabricToForgeTransformer.
     */
    public static <T> T virtualRegister(ResourceKey<?> registryKey, ResourceLocation id, T object) {
        // ИСПРАВЛЕНО: Используем SRG-имя m_135782_() вместо location() для совместимости с Forge 1.20.1
        System.out.println("\033[1;33m[InterMed Registry] Перехвачена регистрация: " + id + " для реестра " + registryKey.m_135782_() + "\033[0m");
        
        // ТЗ 3.2.2: Виртуализация реестров (Шардирование пространств имен)
        // Запрашиваем безопасный виртуальный ID у сервиса
        String namespacePath = id.getNamespace() + ":" + id.getPath();
        int virtualId = VirtualRegistryService.resolveVirtualId(namespacePath, -1);

        // Кладем объект в очередь для последующего сброса в шину Forge
        QUEUE.computeIfAbsent(registryKey, k -> new ArrayList<>())
             .add(new PendingRegistration(id, object));
             
        // Возвращаем объект обратно, чтобы инициализация Fabric-мода продолжалась без ошибок
        return object;
    }

    /**
     * Сброс очереди в Forge (Раздел 3.2.2 ТЗ).
     * Вызывается при срабатывании RegisterEvent в Forge.
     */
    public static void flushQueueToForge(ResourceKey<?> registryKey, Object forgeRegisterEvent) {
        List<PendingRegistration> pending = QUEUE.remove(registryKey);
        if (pending != null && !pending.isEmpty()) {
            System.out.println("[InterMed Bridge] Сбрасываем " + pending.size() + " объектов в Forge...");
            try {
                // Ищем метод только один раз
                if (cachedRegisterMethod == null) {
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    cachedRegisterMethod = lookup.findVirtual(forgeRegisterEvent.getClass(), "register",
                            MethodType.methodType(void.class, ResourceLocation.class, Object.class));
                }

                for (PendingRegistration reg : pending) {
                    cachedRegisterMethod.invoke(forgeRegisterEvent, reg.id(), reg.instance());
                }
            } catch (Throwable t) {
                System.err.println("[InterMed Bridge] Ошибка при сбросе реестра: " + t.getMessage());
            }
        }
    }

    /**
     * Контейнер для отложенной регистрации.
     */
    private record PendingRegistration(ResourceLocation id, Object instance) {}
}