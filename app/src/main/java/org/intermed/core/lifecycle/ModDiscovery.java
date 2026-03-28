package org.intermed.core.lifecycle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ModDiscovery {
    public static List<File> discoverJars() {
        List<File> jars = new ArrayList<>();
        
        String appData = System.getenv("APPDATA");
        File modsDir = new File(appData, ".minecraft/intermed_mods");
        // Папка для кэширования распакованных модулей из Fabric API
        File cacheDir = new File(appData, ".minecraft/.intermed/jij_cache"); 

        System.out.println("\033[1;33m[Discovery] Target Path: " + modsDir.getAbsolutePath() + "\033[0m");

        if (!modsDir.exists()) modsDir.mkdirs();
        if (!cacheDir.exists()) cacheDir.mkdirs();
        
        File[] files = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files != null) {
            for (File f : files) {
                System.out.println("\033[1;32m[Discovery] IDENTIFIED: " + f.getName() + "\033[0m");
                jars.add(f);
                
                // РАСПАКОВКА JAR-IN-JAR (Магия для Fabric API)
                extractJarsInside(f, cacheDir, jars);
            }
        }
        return jars;
    }

    private static void extractJarsInside(File parentJar, File cacheDir, List<File> allJars) {
        try (JarFile jar = new JarFile(parentJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                // Fabric хранит модули в папке META-INF/jars/
                if (!entry.isDirectory() && entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
                    
                    String innerName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    File extractedJar = new File(cacheDir, innerName);
                    
                    // Распаковываем на диск, если еще не делали этого
                    if (!extractedJar.exists() || extractedJar.length() == 0) {
                        try (InputStream is = jar.getInputStream(entry);
                             FileOutputStream fos = new FileOutputStream(extractedJar)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                    
                    System.out.println("\033[1;36m[Discovery] Unpacked nested module: " + innerName + "\033[0m");
                    allJars.add(extractedJar);
                }
            }
        } catch (Exception e) {
            System.err.println("[Discovery] Skip nested parsing for " + parentJar.getName());
        }
    }
}