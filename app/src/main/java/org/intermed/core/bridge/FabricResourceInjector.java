/**
package org.intermed.core.bridge;

import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.network.chat.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Виртуальная файловая система (Раздел 3.2.6 ТЗ).
 * Динамически монтирует ассеты, обходя строгую типизацию Forge при компиляции.

public class FabricResourceInjector implements RepositorySource {
    private final File modsDirectory;

    public FabricResourceInjector(File dir) { 
        this.modsDirectory = dir; 
    }

    @Override
    public void m_7686_(Consumer<Pack> packAcceptor, Pack.PackConstructor packConstructor) {
        System.out.println("\033[1;36m[InterMed VFS] Монтирование виртуальной файловой системы...\033[0m");
        
        // Забираем моды, которые нашел наш сканер
        var fabricMods = org.intermed.core.launch.InterMedTransformationService.FABRIC_MODS;
        
        if (fabricMods == null || fabricMods.isEmpty()) {
            System.out.println("  [!] Нет Fabric-модов для монтирования ресурсов.");
            return;
        }

        for (Path modPath : fabricMods) {
            try {
                String packId = "intermed_" + modPath.getFileName().toString().replace(".jar", "");
                Component title = Component.m_237113_("Fabric Asset: " + modPath.getFileName());

                Class<?> infoClass = Class.forName("net.minecraft.server.packs.repository.Pack$Info");
                Class<?> compatClass = Class.forName("net.minecraft.server.packs.PackCompatibility");
                Class<?> flagsClass = Class.forName("net.minecraft.world.flag.FeatureFlagSet");
                
                Object emptyFlags = flagsClass.getMethod("m_245239_").invoke(null); // ofEmpty()
                Object compat = compatClass.getMethod("m_143754_", int.class).invoke(null, 15); // Для 1.20.1
                
                Constructor<?> infoConstr = infoClass.getConstructors()[0];
                Object packInfo = infoConstr.newInstance(title, compat, emptyFlags, false);

                Method createMethod = null;
                for (Method m : Pack.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 7 && m.getReturnType() == Pack.class) {
                        createMethod = m;
                        break;
                    }
                }

                if (createMethod != null) {
                    Pack pack = (Pack) createMethod.invoke(null, 
                        packId, 
                        title, 
                        true, 
                        // ИСПРАВЛЕНО: Передаем только ОДИН аргумент (modPath.toFile()), как требует компилятор
                        java.lang.reflect.Proxy.newProxyInstance(
                            Pack.class.getClassLoader(),
                            new Class<?>[] { Class.forName("net.minecraft.server.packs.repository.Pack$ResourcesSupplier") },
                            (proxy, method, args) -> new net.minecraft.server.packs.FilePackResources(modPath.toFile())
                        ),
                        packInfo,
                        net.minecraft.server.packs.PackType.CLIENT_RESOURCES,
                        Pack.Position.TOP,
                        net.minecraft.server.packs.repository.PackSource.f_10528_ // BUILT_IN
                    );
                    
                    if (pack != null) {
                        packAcceptor.accept(pack);
                        System.out.println("\033[1;32m  [+] VFS: Текстуры и модели загружены для " + packId + "\033[0m");
                    }
                }
            } catch (Exception e) {
                System.err.println("[InterMed VFS] Ошибка монтирования: " + e.getMessage());
            }
        }
    }
}
*/