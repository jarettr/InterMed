package org.intermed.core.classloading;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers library JARs from a pre-scanned list and builds a
 * {@link LibraryConflictGraph} ready for {@link WelshPowellClusterer}
 * (ТЗ 3.2.1, Requirement 1).
 *
 * <h3>Library identification</h3>
 * A JAR is classified as a <em>library</em> (not a mod) when it contains
 * neither {@code fabric.mod.json}, {@code META-INF/mods.toml}, nor
 * {@code META-INF/neoforge.mods.toml}.
 *
 * Coordinates are resolved with the following priority:
 * <ol>
 *   <li>{@code META-INF/maven/.../pom.properties} — authoritative Maven metadata.</li>
 *   <li>Filename heuristic — {@code name-X.Y.Z.jar} is split into name + version.</li>
 *   <li>Last resort — full filename with version {@code "0.0.0"}.</li>
 * </ol>
 *
 * <h3>Conflict detection</h3>
 * Two {@link LibraryNode}s receive a conflict edge when they share the same
 * {@code groupId:artifactId} but carry different version strings.  If PubGrub
 * resolved every library to exactly one version the graph will have no edges
 * and all libraries end up in a single shared shader.
 *
 * Additionally, JARs that export overlapping Java package namespaces receive
 * conflict edges, preventing silent class shadowing at the ClassLoader level.
 */
public final class LibraryDiscovery {

    public static final String GLOBAL_VISIBILITY_DOMAIN = "global";

    /** Matches {@code name-1.2.3[-qualifier].jar} filenames. */
    private static final Pattern NAME_VER = Pattern.compile(
        "^(.+?)-(\\d+(?:[._\\-]\\w+)*)(?:\\.jar)?$",
        Pattern.CASE_INSENSITIVE
    );

    private LibraryDiscovery() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Inspects every JAR in {@code allJars}, separates library JARs from mod
     * JARs, and constructs a fully populated {@link LibraryConflictGraph}.
     *
     * @param allJars Full list of JARs produced by
     *                {@link org.intermed.core.lifecycle.ModDiscovery}.
     * @return A conflict graph ready to be passed to {@link WelshPowellClusterer}.
     */
    public static LibraryConflictGraph discover(List<File> allJars) {
        return discover(allJars, jar -> GLOBAL_VISIBILITY_DOMAIN);
    }

    public static LibraryConflictGraph discover(List<File> allJars,
                                                Function<File, String> visibilityDomainResolver) {
        LibraryConflictGraph graph = new LibraryConflictGraph();

        // visibilityDomain + artifactId → all discovered versions of that artifact
        Map<String, List<LibraryNode>> byArtifact = new HashMap<>();
        // visibilityDomain + root package → library that first claimed it
        Map<String, LibraryNode> packageOwners = new HashMap<>();
        // visibilityDomain → nodes in that scope, used to prevent cross-scope sharing
        Map<String, List<LibraryNode>> byVisibilityDomain = new LinkedHashMap<>();

        for (File jar : allJars) {
            if (jar == null || !jar.exists() || !jar.isFile()) continue;
            if (isMod(jar)) continue;   // mods are handled separately by LifecycleManager

            String visibilityDomain = normalizeVisibilityDomain(
                visibilityDomainResolver == null ? null : visibilityDomainResolver.apply(jar)
            );
            LibraryNode node = identifyLibrary(jar, visibilityDomain);
            graph.addLibrary(node);
            byArtifact.computeIfAbsent(visibilityDomain + '\u0000' + node.artifactId, k -> new ArrayList<>()).add(node);
            byVisibilityDomain.computeIfAbsent(visibilityDomain, k -> new ArrayList<>()).add(node);

            // ── Split-package detection ───────────────────────────────────────
            // Two JARs that export the same root package conflict even when their
            // artifact IDs differ (e.g. two different slf4j shims).
            Set<String> rootPackages = scanRootPackages(jar);
            for (String pkg : rootPackages) {
                String scopedPackage = visibilityDomain + '\u0000' + pkg;
                LibraryNode prior = packageOwners.putIfAbsent(scopedPackage, node);
                if (prior != null && !prior.equals(node)) {
                    graph.addConflict(prior, node);
                    System.out.printf(
                        "[LibraryDiscovery] Package-conflict edge: \"%s\" claimed by %s AND %s%n",
                        pkg, prior, node
                    );
                }
            }
        }

        // ── Scope-isolation edges: libraries from different owners must never share
        //    the same shader loader even when they are otherwise conflict-free.
        List<Map.Entry<String, List<LibraryNode>>> domains = new ArrayList<>(byVisibilityDomain.entrySet());
        for (int i = 0; i < domains.size(); i++) {
            for (int j = i + 1; j < domains.size(); j++) {
                List<LibraryNode> left = domains.get(i).getValue();
                List<LibraryNode> right = domains.get(j).getValue();
                for (LibraryNode a : left) {
                    for (LibraryNode b : right) {
                        graph.addConflict(a, b);
                    }
                }
                System.out.printf(
                    "[LibraryDiscovery] Visibility scopes isolated: %s (%d) vs %s (%d)%n",
                    domains.get(i).getKey(), left.size(), domains.get(j).getKey(), right.size()
                );
            }
        }

        // ── Version-conflict edges: same artifact, different versions ─────────
        for (Map.Entry<String, List<LibraryNode>> entry : byArtifact.entrySet()) {
            List<LibraryNode> versions = entry.getValue();
            if (versions.size() < 2) continue;

            for (int i = 0; i < versions.size(); i++) {
                for (int j = i + 1; j < versions.size(); j++) {
                    graph.addConflict(versions.get(i), versions.get(j));
                    System.out.printf("[LibraryDiscovery] Version-conflict edge: %s vs %s%n",
                        versions.get(i), versions.get(j));
                }
            }
        }

        System.out.printf(
            "[LibraryDiscovery] Discovered %d library nodes, %d conflict edges.%n",
            graph.size(), graph.edgeCount()
        );
        return graph;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the JAR contains Fabric or Forge mod metadata,
     * meaning it should be handled as a mod, not a library.
     */
    private static boolean isMod(File jar) {
        try (JarFile jf = new JarFile(jar, false)) {
            return jf.getJarEntry("fabric.mod.json") != null
                || jf.getJarEntry("META-INF/mods.toml") != null
                || jf.getJarEntry("META-INF/neoforge.mods.toml") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Attempts to identify a library JAR by its Maven coordinates, falling back
     * to filename-based heuristics.
     *
     * @return A non-null {@link LibraryNode} — never {@code null}.
     */
    static LibraryNode identifyLibrary(File jar) {
        return identifyLibrary(jar, GLOBAL_VISIBILITY_DOMAIN);
    }

    static LibraryNode identifyLibrary(File jar, String visibilityDomain) {
        // Priority 1: META-INF/maven/.../pom.properties
        try (JarFile jf = new JarFile(jar, false)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith("META-INF/maven/") && n.endsWith("/pom.properties")) {
                    Properties props = new Properties();
                    try (InputStream is = jf.getInputStream(e)) {
                        props.load(is);
                    }
                    String gid     = props.getProperty("groupId",    "unknown");
                    String aid     = props.getProperty("artifactId", "unknown");
                    String version = props.getProperty("version",    "0.0.0");
                    return new LibraryNode(gid + ":" + aid, version, jar, visibilityDomain);
                }
            }
        } catch (Exception ignored) {}

        // Priority 2: filename heuristic — e.g. "guava-32.1.3-jre.jar"
        String filename = jar.getName();
        if (filename.toLowerCase().endsWith(".jar")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        Matcher m = NAME_VER.matcher(filename);
        if (m.matches()) {
            return new LibraryNode("unknown:" + m.group(1), m.group(2), jar, visibilityDomain);
        }

        // Last resort: use the full filename with a synthetic version
        return new LibraryNode("unknown:" + jar.getName(), "0.0.0", jar, visibilityDomain);
    }

    /**
     * Scans a JAR and returns the set of <em>top-level</em> Java packages it
     * contains (at most two levels, e.g. {@code "com.google"}).
     * This is used for split-package conflict detection.
     */
    private static Set<String> scanRootPackages(File jar) {
        Set<String> packages = new HashSet<>();
        try (JarFile jf = new JarFile(jar, false)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                // Strip class filename; keep up to two package levels
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash < 0) continue;
                String pkg = name.substring(0, lastSlash); // e.g. "com/google/common/collect"
                // Reduce to root two segments: "com/google"
                String[] parts = pkg.split("/");
                if (parts.length >= 2) {
                    packages.add(parts[0] + "/" + parts[1]);
                } else if (parts.length == 1) {
                    packages.add(parts[0]);
                }
            }
        } catch (Exception ignored) {}
        return packages;
    }

    public static String ownerVisibilityDomain(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return GLOBAL_VISIBILITY_DOMAIN;
        }
        return "owner:" + ownerId.trim().toLowerCase();
    }

    static String normalizeVisibilityDomain(String visibilityDomain) {
        if (visibilityDomain == null || visibilityDomain.isBlank()) {
            return GLOBAL_VISIBILITY_DOMAIN;
        }
        return visibilityDomain.trim().toLowerCase();
    }
}
