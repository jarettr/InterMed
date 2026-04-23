package org.intermed.core.lifecycle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class ModDiscovery {
    public record DiscoveryLayout(List<File> jars, Map<Path, Path> nestedJarOwners) {
        public DiscoveryLayout {
            jars = List.copyOf(new ArrayList<>(jars));
            nestedJarOwners = Map.copyOf(new LinkedHashMap<>(nestedJarOwners));
        }

        public Path ownerOf(File jar) {
            if (jar == null) {
                return null;
            }
            return nestedJarOwners.get(normalize(jar));
        }
    }

    public static List<File> discoverJars() {
        return discoverLayout().jars();
    }

    public static DiscoveryLayout discoverLayout() {
        RuntimeConfig.reload();
        return discoverLayout(RuntimeConfig.get().getModsDir().toFile());
    }

    public static List<File> discoverJars(File modsDir) {
        return discoverLayout(modsDir).jars();
    }

    public static List<File> discoverCandidateArchives(File modsDir) {
        DiscoveryLayout layout = discoverLayout(modsDir);
        List<File> archives = new ArrayList<>(layout.jars());
        File[] zips = modsDir.listFiles((dir, name) -> isZipName(name));
        if (zips != null) {
            for (File zip : zips) {
                System.out.println("\033[1;32m[Discovery] IDENTIFIED CANDIDATE ARCHIVE: " + zip.getName() + "\033[0m");
                archives.add(zip);
            }
        }
        return archives;
    }

    public static DiscoveryLayout discoverLayout(File modsDir) {
        List<File> jars = new ArrayList<>();
        Map<Path, Path> nestedJarOwners = new LinkedHashMap<>();
        Set<Path> discoveredPaths = new HashSet<>();
        Map<String, File> nestedJarByHash = new HashMap<>();
        Map<NestedModuleIdentity, File> nestedJarByIdentity = new HashMap<>();
        File cacheDir = new File(modsDir, ".intermed/jij_cache");

        System.out.println("\033[1;33m[Discovery] Target Path: " + modsDir.getAbsolutePath() + "\033[0m");

        if (!modsDir.exists()) modsDir.mkdirs();
        if (!cacheDir.exists()) cacheDir.mkdirs();
        
        File[] files = modsDir.listFiles((dir, name) -> isJarName(name));
        if (files != null) {
            for (File f : files) {
                System.out.println("\033[1;32m[Discovery] IDENTIFIED: " + f.getName() + "\033[0m");
                addIfAbsent(jars, discoveredPaths, f);
                
                // РАСПАКОВКА JAR-IN-JAR (Магия для Fabric API)
                extractJarsInside(f, cacheDir, jars, nestedJarOwners, discoveredPaths, nestedJarByHash, nestedJarByIdentity);
            }
        }
        return new DiscoveryLayout(jars, nestedJarOwners);
    }

    private static void extractJarsInside(File parentJar,
                                          File cacheDir,
                                          List<File> allJars,
                                          Map<Path, Path> nestedJarOwners,
                                          Set<Path> discoveredPaths,
                                          Map<String, File> nestedJarByHash,
                                          Map<NestedModuleIdentity, File> nestedJarByIdentity) {
        try (JarFile jar = new JarFile(parentJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                // Fabric хранит модули в папке META-INF/jars/
                if (!entry.isDirectory() && entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
                    String innerName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    byte[] nestedBytes;
                    try (InputStream is = jar.getInputStream(entry)) {
                        nestedBytes = is.readAllBytes();
                    }
                    String contentHash = sha256(nestedBytes);
                    File duplicate = nestedJarByHash.get(contentHash);
                    if (duplicate != null) {
                        System.out.println("\033[1;36m[Discovery] Reusing nested module: " + innerName
                            + " (deduped against " + duplicate.getName() + ")\033[0m");
                        addIfAbsent(allJars, discoveredPaths, duplicate);
                        continue;
                    }
                    NestedModuleIdentity identity = readNestedModuleIdentity(nestedBytes);
                    if (identity != null) {
                        File identityDuplicate = nestedJarByIdentity.get(identity);
                        if (identityDuplicate != null) {
                            System.out.println("\033[1;36m[Discovery] Reusing nested module: " + innerName
                                + " (metadata-deduped against " + identityDuplicate.getName() + ")\033[0m");
                            addIfAbsent(allJars, discoveredPaths, identityDuplicate);
                            continue;
                        }
                    }

                    File extractedJar = new File(cacheDir, uniqueNestedJarName(parentJar, entry, innerName));
                    
                    // Распаковываем на диск, если еще не делали этого
                    if (!extractedJar.exists() || extractedJar.length() == 0) {
                        try (FileOutputStream fos = new FileOutputStream(extractedJar)) {
                            fos.write(nestedBytes);
                        }
                    }
                    
                    System.out.println("\033[1;36m[Discovery] Unpacked nested module: " + innerName + "\033[0m");
                    nestedJarByHash.put(contentHash, extractedJar);
                    if (identity != null) {
                        nestedJarByIdentity.put(identity, extractedJar);
                    }
                    addIfAbsent(allJars, discoveredPaths, extractedJar);
                    nestedJarOwners.put(normalize(extractedJar), normalize(parentJar));
                }
            }
        } catch (Exception e) {
            System.err.println("[Discovery] Skip nested parsing for " + parentJar.getName());
        }
    }

    private static String uniqueNestedJarName(File parentJar, JarEntry entry, String innerName) {
        String prefix = parentJar.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        String entryName = entry == null ? innerName : entry.getName();
        long entryCrc = entry == null ? 0L : entry.getCrc();
        long entrySize = entry == null ? 0L : entry.getSize();
        String fingerprint = UUID.nameUUIDFromBytes(
            (parentJar.getAbsolutePath()
                + "::" + parentJar.length()
                + "::" + parentJar.lastModified()
                + "::" + entryName
                + "::" + entryCrc
                + "::" + entrySize).getBytes(StandardCharsets.UTF_8)
        ).toString().replace("-", "").substring(0, 12);
        return prefix + "-" + fingerprint + "-" + innerName;
    }

    private static Path normalize(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

    private static void addIfAbsent(List<File> jars, Set<Path> discoveredPaths, File jar) {
        if (jar == null) {
            return;
        }
        Path normalized = normalize(jar);
        if (discoveredPaths.add(normalized)) {
            jars.add(jar);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }

    private static boolean isJarName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static boolean isZipName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
    }

    private static NestedModuleIdentity readNestedModuleIdentity(byte[] nestedJarBytes) {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(nestedJarBytes))) {
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (!entry.isDirectory() && "fabric.mod.json".equals(entry.getName())) {
                    JsonObject json = JsonParser.parseReader(new InputStreamReader(jar, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                    String modId = json.has("id") ? json.get("id").getAsString() : null;
                    if (modId == null || modId.isBlank()) {
                        return null;
                    }
                    String version = json.has("version") ? json.get("version").getAsString() : "0.0.0";
                    boolean generated = false;
                    if (json.has("custom") && json.get("custom").isJsonObject()) {
                        JsonObject custom = json.getAsJsonObject("custom");
                        if (custom.has("fabric-loom:generated") && custom.get("fabric-loom:generated").isJsonPrimitive()) {
                            generated = custom.get("fabric-loom:generated").getAsBoolean();
                        }
                    }
                    if (!generated) {
                        return null;
                    }
                    return new NestedModuleIdentity(modId, version);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private record NestedModuleIdentity(String modId, String version) {}
}
