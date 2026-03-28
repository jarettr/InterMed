package org.intermed.core.bridge;

import org.intermed.core.boot.FabricBootstrapper;
import org.intermed.core.classloader.InterMedClassLoader;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FabricLifecycleBridge {
    private static InterMedClassLoader dagLoader;
    private static final Path TEMP_DIR = Paths.get("intermed_cache/unpacked_jars");

    public static void bootMainEntrypoints() {
        if (dagLoader != null) return;
        
        try {
            System.out.println("\033[1;35m[InterMed Lifecycle] Initiating Hybrid Boot (Matryoshka Engine)...\033[0m");
            
            // Создаем папку кэша, если её нет
            if (!Files.exists(TEMP_DIR)) Files.createDirectories(TEMP_DIR);

            File fabricDir = new File(System.getProperty("user.dir"), "mods/fabric");
            File[] files = fabricDir.listFiles((dir, name) -> name.endsWith(".jar"));
            
            if (files == null || files.length == 0) {
                System.out.println("[Lifecycle] No Fabric mods found in /mods/fabric/");
                return;
            }

            // ШАГ 1: Собираем ВСЕ URL (включая вложенные в Fabric API)
            List<URL> allUrls = new ArrayList<>();
            for (File f : files) {
                allUrls.add(f.toURI().toURL());
                
                // Если это Fabric API, распаковываем его внутренние модули
                if (f.getName().toLowerCase().contains("fabric-api")) {
                    System.out.println("[Lifecycle] Unpacking Fabric API modules...");
                    allUrls.addAll(extractNestedJars(f));
                }
            }
            
            ClassLoader originalForgeLoader = Thread.currentThread().getContextClassLoader();
            Instrumentation inst = (Instrumentation) System.getProperties().get("intermed.instrumentation");

            // Создаем DAG-загрузчик, который видит и моды, и все части Fabric API
            dagLoader = new InterMedClassLoader("InterMed-Universal-DAG", allUrls.toArray(new URL[0]), originalForgeLoader);

            if (inst != null) {
                InterMedJpmsBypass.crackModule(inst, dagLoader);
            }

            // ШАГ 2: Запуск точек входа
            for (File f : files) {
                // Fabric API не инициализируем как мод, он нужен только как библиотека
                if (f.getName().toLowerCase().contains("fabric-api")) continue;

                String jsonContent = readFabricModJson(f);
                if (jsonContent == null) continue;

                List<String> toBoot = new ArrayList<>();
                toBoot.addAll(extractEntrypoints(jsonContent, "main"));
                toBoot.addAll(extractEntrypoints(jsonContent, "client"));

                if (!toBoot.isEmpty()) {
                    System.out.println("\033[1;36m[Lifecycle] Awakening mod: " + f.getName() + "\033[0m");
                    Thread.currentThread().setContextClassLoader(dagLoader);
                    try {
                        for (String className : toBoot) {
                            String cleanName = className.split("::")[0];
                            FabricBootstrapper.boot(cleanName, dagLoader);
                        }
                    } finally {
                        Thread.currentThread().setContextClassLoader(originalForgeLoader);
                    }
                }
            }
            
        } catch (Throwable e) {
            System.err.println("[Lifecycle] CRITICAL FAILURE during boot:");
            e.printStackTrace();
        }
    }

    /**
     * Распаковка вложенных JAR-файлов из Fabric API (Req 3.2.3).
     */
    private static List<URL> extractNestedJars(File jarFile) throws IOException {
        List<URL> nested = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // Модули Fabric API обычно лежат в META-INF/jars/
                if (entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
                    String simpleName = new File(entry.getName()).getName();
                    Path targetPath = TEMP_DIR.resolve(simpleName);
                    
                    // Копируем во временную папку, чтобы Java могла их прочитать
                    if (!Files.exists(targetPath)) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    nested.add(targetPath.toUri().toURL());
                    System.out.println("\033[1;32m  [+] Unpacked: " + simpleName + "\033[0m");
                }
            }
        }
        return nested;
    }

    private static String readFabricModJson(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) { return null; }
    }

    private static List<String> extractEntrypoints(String json, String type) {
        List<String> results = new ArrayList<>();
        // Регулярка для поддержки и массивов ["class"], и одиночных строк "class"
        Pattern pattern = Pattern.compile("\"" + type + "\"\\s*:\\s*(?:\\[(.*?)\\]|\"([^\"]+)\")", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String content = (matcher.group(1) != null) ? matcher.group(1) : "\"" + matcher.group(2) + "\"";
            Matcher classMatcher = Pattern.compile("\"([^\"]+)\"").matcher(content);
            while (classMatcher.find()) {
                results.add(classMatcher.group(1));
            }
        }
        return results;
    }
}