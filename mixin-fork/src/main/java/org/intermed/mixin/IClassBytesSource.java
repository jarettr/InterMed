package org.intermed.mixin;

import java.io.IOException;
import java.net.URL;

/**
 * Abstraction over the InterMed ClassLoader DAG, allowing the Mixin fork
 * service to locate classes without a compile-time dependency on the
 * {@code app} module (which would create a circular dependency).
 *
 * <p>An instance of this interface is registered by
 * {@link InterMedMixinBootstrap#registerClassSource} early in the boot
 * sequence (from {@code LifecycleManager} or the launcher).
 */
public interface IClassBytesSource {

    /**
     * Returns the raw class bytes for {@code className} (dot-separated), or
     * {@code null} if not found in the DAG.
     */
    byte[] getClassBytes(String className) throws IOException;

    /** Returns {@code true} if the class has already been defined in the DAG. */
    boolean isClassLoaded(String className);

    /**
     * Loads (and optionally initialises) a class from the DAG.
     * Delegates to the mod class loaders in topological order.
     */
    Class<?> findClass(String className) throws ClassNotFoundException;

    /**
     * Returns the effective class-path URLs visible to the DAG.
     * Used by {@link org.intermed.mixin.service.InterMedClassProvider}.
     */
    URL[] getClassPath();
}
