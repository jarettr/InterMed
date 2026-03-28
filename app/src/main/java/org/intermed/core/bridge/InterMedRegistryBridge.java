package org.intermed.core.bridge;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import java.lang.invoke.*;
import java.lang.reflect.Method;

public class InterMedRegistryBridge {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static MethodHandle keyGetter, locGetter, registerHandle;

    public static void setup() {
        try {
            Class<?> regClass = Class.forName("net.minecraft.core.Registry");
            keyGetter = LOOKUP.unreflect(findMethod(regClass, "key", "m_123023_"));
            System.out.println("\033[1;32m[InterMed Registry] Мост прямой ранней интеграции готов. NPE устранен!\033[0m");
        } catch (Exception e) { e.printStackTrace(); }
    }

    @SuppressWarnings("rawtypes")
    public static Object register(Registry reg, ResourceLocation name, Object val) {
        try {
            Object resKey = keyGetter.invoke(reg);
            if (locGetter == null) locGetter = LOOKUP.unreflect(findMethod(resKey.getClass(), "location", "m_135782_"));
            ResourceLocation regId = (ResourceLocation) locGetter.invoke(resKey);
            
            // Находим открытый реестр Forge
            Class<?> rm = Class.forName("net.minecraftforge.registries.RegistryManager");
            Object active = rm.getField("ACTIVE").get(null);
            Method getReg = rm.getMethod("getRegistry", ResourceLocation.class);
            Object forgeReg = getReg.invoke(active, regId);

            if (forgeReg != null) {
                // Мгновенная интеграция без EventBus
                if (registerHandle == null) {
                    registerHandle = LOOKUP.findVirtual(forgeReg.getClass(), "register", 
                        MethodType.methodType(void.class, ResourceLocation.class, Object.class));
                }
                registerHandle.invoke(forgeReg, name, val);
                System.out.println("\033[1;32m  [+] Предмет интегрирован напрямую: " + name + "\033[0m");
            }
        } catch (Throwable t) { t.printStackTrace(); }
        return val;
    }

    private static Method findMethod(Class<?> clazz, String name, String srg) throws NoSuchMethodException {
        try { return clazz.getMethod(name); } 
        catch (NoSuchMethodException e) {
            for (Method m : clazz.getMethods()) if (m.getName().equals(srg)) return m;
            throw new NoSuchMethodException(name);
        }
    }
}