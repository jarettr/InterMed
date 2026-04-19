package org.intermed.mixin.service;

import org.intermed.mixin.InterMedMixinBootstrap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link IClassBytecodeProvider} implementation for the InterMed Mixin fork.
 *
 * <p>Attempts to retrieve class bytes in order:
 * <ol>
 *   <li>InterMed ClassLoader DAG (mod JARs, transformed bytes)</li>
 *   <li>Thread context class loader resource stream (vanilla/Minecraft classes)</li>
 * </ol>
 */
public final class InterMedBytecodeProvider implements IClassBytecodeProvider {

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {

        byte[] bytes = getBytesFor(name);
        if (bytes == null) {
            throw new ClassNotFoundException("InterMedBytecodeProvider: class not found: " + name);
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    // -------------------------------------------------------------------------

    private static byte[] getBytesFor(String name) throws IOException {
        // 1. Try the ClassLoader DAG
        var src = InterMedMixinBootstrap.getClassSource();
        if (src != null) {
            byte[] bytes = src.getClassBytes(name);
            if (bytes != null) return bytes;
        }

        // 2. Try the context class loader's resource stream
        String path = name.replace('.', '/') + ".class";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            try (InputStream is = cl.getResourceAsStream(path)) {
                if (is != null) return is.readAllBytes();
            }
        }

        ClassLoader serviceLoader = InterMedBytecodeProvider.class.getClassLoader();
        if (serviceLoader != null && serviceLoader != cl) {
            try (InputStream is = serviceLoader.getResourceAsStream(path)) {
                if (is != null) return is.readAllBytes();
            }
        }

        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != null && systemLoader != cl && systemLoader != serviceLoader) {
            try (InputStream is = systemLoader.getResourceAsStream(path)) {
                if (is != null) return is.readAllBytes();
            }
        }

        return null;
    }
}
