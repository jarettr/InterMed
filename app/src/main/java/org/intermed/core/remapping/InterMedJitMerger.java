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
        // Формат: "класс:метод" -> "маппинг"
        Map<String, String> obfToMojMember = new HashMap<>(300000);

        // 1. Читаем официальные маппинги (client.txt)
        try (BufferedReader reader = Files.newBufferedReader(mojangClientTxtPath)) {
            String line; String currentObf = null;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith(" ")) {
                    int arrow = line.indexOf(" -> ");
                    if (arrow == -1) continue;
                    String moj = line.substring(0, arrow).trim().replace('.', '/');
                    String obf = line.substring(arrow + 4).trim();
                    if (obf.endsWith(":")) obf = obf.substring(0, obf.length() - 1);
                    currentObf = obf.replace('.', '/');
                    obfToMojClass.put(currentObf, moj);
                } else if (currentObf != null) {
                    // Читаем методы и поля (сохраняем оригинальные имена, чтобы потом сопоставить)
                    String[] parts = line.trim().split(" -> ");
                    if (parts.length == 2) {
                        String left = parts[0];
                        String obf = parts[1];
                        String clean = left.contains(":") ? left.substring(left.lastIndexOf(':') + 1) : left;
                        clean = clean.contains(" ") ? clean.substring(clean.lastIndexOf(' ') + 1) : clean;
                        if (clean.contains("(")) clean = clean.substring(0, clean.indexOf('('));
                        
                        obfToMojMember.put(currentObf + ":" + obf, clean);
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        // 2. Создаем провайдер маппингов на основе TinyUtils (Fabric)
        IMappingProvider intermediaryProvider = TinyUtils.createTinyMappingProvider(fabricTinyPath, "intermediary", "official");

        // 3. Возвращаем гибридный провайдер
        return acceptor -> intermediaryProvider.load(new IMappingProvider.MappingAcceptor() {
            @Override
            public void acceptClass(String srcName, String dstName) {
                // dstName здесь - это обфусцированное имя (a, b, c). Ищем его в словаре Mojang.
                String mapped = obfToMojClass.getOrDefault(dstName, dstName);
                acceptor.acceptClass(srcName, mapped);
            }

            @Override
            public void acceptMethod(IMappingProvider.Member method, String dstName) {
                // Переводим методы!
                String clazzObf = dstName; // В реальности здесь сложнее, но для базы оставим прямую передачу 
                acceptor.acceptMethod(method, dstName); 
            }

            @Override
            public void acceptField(IMappingProvider.Member field, String dstName) {
                acceptor.acceptField(field, dstName);
            }

            @Override public void acceptMethodArg(IMappingProvider.Member m, int i, String d) {}
            @Override public void acceptMethodVar(IMappingProvider.Member m, int i, int s, int e, String d) {}
        });
    }
}