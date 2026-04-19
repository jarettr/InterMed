package org.intermed.core.remapping;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class InterMedJitMerger {
    public static IMappingProvider createBridgedProvider(Path fabricTinyPath, Path mojangClientTxtPath) {
        System.out.println("\033[1;36m[Fusion Engine] Initializing Deep Mappings (Intermediary -> SRG/Mojang)...\033[0m");

        Map<String, String> obfToMojClass = new HashMap<>(32000);
        // Key: "obfClassName:obfMemberName" -> mojang member name
        Map<String, String> obfToMojMember = new HashMap<>(300000);

        // 1. Parse Mojang official mappings (client.txt / ProGuard format)
        try (BufferedReader reader = Files.newBufferedReader(mojangClientTxtPath)) {
            String line;
            String currentObf = null;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith(" ")) {
                    // Class mapping: "net.minecraft.Foo -> a:"
                    int arrow = line.indexOf(" -> ");
                    if (arrow == -1) continue;
                    String moj = line.substring(0, arrow).trim().replace('.', '/');
                    String obf = line.substring(arrow + 4).trim();
                    if (obf.endsWith(":")) obf = obf.substring(0, obf.length() - 1);
                    currentObf = obf.replace('.', '/');
                    obfToMojClass.put(currentObf, moj);
                } else if (currentObf != null) {
                    // Member mapping: "    int tick() -> a" or "    int field -> b"
                    String[] parts = line.trim().split(" -> ");
                    if (parts.length == 2) {
                        String left   = parts[0].trim();
                        String obfMem = parts[1].trim();
                        // Strip return type and line numbers: "int tick()" -> "tick"
                        String clean = left.contains(":") ? left.substring(left.lastIndexOf(':') + 1) : left;
                        clean = clean.contains(" ") ? clean.substring(clean.lastIndexOf(' ') + 1) : clean;
                        if (clean.contains("(")) clean = clean.substring(0, clean.indexOf('('));
                        obfToMojMember.put(currentObf + ":" + obfMem, clean);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. Fabric Tiny provider: intermediary -> official (obfuscated)
        IMappingProvider intermediaryProvider =
            TinyUtils.createTinyMappingProvider(fabricTinyPath, "intermediary", "official");

        // Mutable map populated during acceptClass so acceptMethod/acceptField can
        // resolve the obfuscated owner from the intermediary owner name.
        Map<String, String> intermediaryToObfClass = new HashMap<>(32000);

        // 3. Hybrid provider: intermediary -> Mojang (deobfuscated)
        return acceptor -> intermediaryProvider.load(new IMappingProvider.MappingAcceptor() {

            @Override
            public void acceptClass(String srcName, String dstName) {
                // srcName = intermediary ("net/minecraft/class_1234")
                // dstName = obfuscated  ("abc")
                intermediaryToObfClass.put(srcName, dstName);
                String mojName = obfToMojClass.getOrDefault(dstName, dstName);
                acceptor.acceptClass(srcName, mojName);
            }

            @Override
            public void acceptMethod(IMappingProvider.Member method, String dstName) {
                // method.owner = intermediary class, dstName = obfuscated method name
                String obfOwner = intermediaryToObfClass.getOrDefault(method.owner, method.owner);
                String mojName  = obfToMojMember.getOrDefault(obfOwner + ":" + dstName, dstName);
                acceptor.acceptMethod(method, mojName);
            }

            @Override
            public void acceptField(IMappingProvider.Member field, String dstName) {
                // field.owner = intermediary class, dstName = obfuscated field name
                String obfOwner = intermediaryToObfClass.getOrDefault(field.owner, field.owner);
                String mojName  = obfToMojMember.getOrDefault(obfOwner + ":" + dstName, dstName);
                acceptor.acceptField(field, mojName);
            }

            @Override
            public void acceptMethodArg(IMappingProvider.Member m, int i, String d) {}

            @Override
            public void acceptMethodVar(IMappingProvider.Member m, int i, int s, int e, String d) {}
        });
    }
}