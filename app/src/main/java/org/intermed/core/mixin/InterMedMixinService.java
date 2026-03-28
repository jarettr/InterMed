package org.intermed.core.mixin;

import org.intermed.core.lifecycle.LifecycleManager;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.util.ReEntranceLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import com.google.common.collect.ImmutableList;

import org.intermed.core.classloading.LazyInterMedClassLoader;

public class InterMedMixinService implements IMixinService, IClassProvider, IClassBytecodeProvider {

    private final ReEntranceLock lock = new ReEntranceLock(1);

    @Override
    public String getName() {
        return "InterMed";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void prepare() {
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public void init() {
    }

    @Override
    public void beginPhase() {
    }

    @Override
    public void checkEnv(Object bootStrap) {
    }

    @Override
    public ReEntranceLock getReEntranceLock() {
        return lock;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return ImmutableList.of("org.intermed.core.mixin.InterMedPlatformAgent");
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override
    public void registerInvalidClass(String name) {
    }

    @Override
    public boolean isClassLoaded(String name) {
        return LifecycleManager.isClassLoadedInDAG(name);
    }

    @Override
    public String getSideName() {
        return "CLIENT";
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return LifecycleManager.findClassInDAG(name);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        // The default implementation of this in Mixin's service just calls the other findClass,
        // so we'll do the same. Initialization will be handled by the VM.
        return findClass(name);
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, InterMedMixinService.class.getClassLoader());
    }

    @Override
    public byte[] getClassBytes(String name, String originalName) throws java.io.IOException {
        return LifecycleManager.getClassBytesFromDAG(name);
    }

    @Override
    public byte[] getClassBytes(String name, boolean runTransformers) throws java.io.IOException {
        // The `runTransformers` flag is a hint. For our model, we assume transformers are
        // already part of the classloader's `defineClass` process, so we can ignore it.
        return getClassBytes(name, name);
    }
}

