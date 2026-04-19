package org.intermed.mixin.service;

import org.intermed.mixin.InterMedMixinBootstrap;
import org.spongepowered.asm.service.IClassProvider;

import java.net.URL;

/**
 * {@link IClassProvider} implementation for the InterMed Mixin fork.
 * Delegates class discovery to the ClassLoader DAG via
 * {@link org.intermed.mixin.IClassBytesSource}.
 */
public final class InterMedClassProvider implements IClassProvider {

    private static ClassLoader fallbackLoader() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            return contextLoader;
        }
        ClassLoader localLoader = InterMedClassProvider.class.getClassLoader();
        return localLoader != null ? localLoader : ClassLoader.getSystemClassLoader();
    }

    @Override
    public URL[] getClassPath() {
        var src = InterMedMixinBootstrap.getClassSource();
        if (src != null) return src.getClassPath();
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        var src = InterMedMixinBootstrap.getClassSource();
        if (src != null) {
            try { return src.findClass(name); } catch (ClassNotFoundException ignored) {}
        }
        // Fallback: context class loader (covers JDK + Minecraft boot classes)
        return Class.forName(name, false, fallbackLoader());
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        var src = InterMedMixinBootstrap.getClassSource();
        if (src != null) {
            try { return src.findClass(name); } catch (ClassNotFoundException ignored) {}
        }
        return Class.forName(name, initialize, fallbackLoader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        // Agent classes live in the bootstrap/system loader
        return Class.forName(name, initialize, ClassLoader.getSystemClassLoader());
    }
}
