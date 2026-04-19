package org.intermed.core.boot;

import org.intermed.core.metrics.ModBootEvent;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Реализация Требования 3.1 (Управление жизненным циклом).
 * Обеспечивает запуск точек входа Fabric-модов внутри изолированного ClassLoader DAG.
 */
public class FabricBootstrapper {
    private static final Set<String> booted = new HashSet<>();

    /**
     * Пробуждает Fabric-мод.
     * @param className Имя класса точки входа (напр. "me.author.mod.ModInit")
     * @param loader Загрузчик из нашего DAG-узла (InterMedTransformingClassLoader)
     */
    public static void boot(String className, ClassLoader loader) {
        if (booted.contains(className)) return;
        booted.add(className);

        System.out.println("\033[1;36m[Bootstrapper] Waking up Fabric Entrypoint: " + className + "\033[0m");
        
        ModBootEvent bootEvent = new ModBootEvent();
        bootEvent.className = className;
        bootEvent.begin();

        try {
            // Загружаем класс мода через изолированный загрузчик
            Class<?> clazz = Class.forName(className, true, loader);
            
            // Создаем экземпляр мода (теперь это не вызовет ошибку, т.к. интерфейсы существуют!)
            Object instance = clazz.getDeclaredConstructor().newInstance();
            
            boolean initialized = false;

            // 1. Попытка запуска Client-части (рендеринг, GUI)
            if (instance instanceof net.fabricmc.api.ClientModInitializer) {
                ((net.fabricmc.api.ClientModInitializer) instance).onInitializeClient();
                System.out.println("\033[1;32m  [+] " + className + " успешно инициализирован (Client Interface).\033[0m");
                initialized = true;
            } else {
                try {
                    Method m = clazz.getMethod("onInitializeClient");
                    m.invoke(instance);
                    System.out.println("\033[1;32m  [+] " + className + " успешно инициализирован (Client Reflection).\033[0m");
                    initialized = true;
                } catch (NoSuchMethodException ignored) {}
            }

            // 2. Попытка запуска Main-части (общая логика)
            if (instance instanceof net.fabricmc.api.ModInitializer) {
                ((net.fabricmc.api.ModInitializer) instance).onInitialize();
                System.out.println("\033[1;32m  [+] " + className + " успешно инициализирован (Main Interface).\033[0m");
                initialized = true;
            } else {
                try {
                    Method m = clazz.getMethod("onInitialize");
                    m.invoke(instance);
                    System.out.println("\033[1;32m  [+] " + className + " успешно инициализирован (Main Reflection).\033[0m");
                    initialized = true;
                } catch (NoSuchMethodException ignored) {}
            }

            if (!initialized) {
                System.out.println("\033[1;33m  [!] Entrypoint [" + className + "] не содержит стандартных методов инициализации.\033[0m");
            }

        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            System.err.println("\033[1;31m[Bootstrapper] Ошибка зависимостей для " + className + ": " + e.getMessage() + "\033[0m");
        } catch (Throwable t) {
            System.err.println("\033[1;31m[Bootstrapper] Фатальная ошибка в логике мода " + className + ":\033[0m");
            t.printStackTrace();
        } finally {
            bootEvent.commit(); // Сохраняем метрику
        }
    }
}