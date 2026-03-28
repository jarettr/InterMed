package org.intermed.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

public class InterMedLauncher {
    public static void main(String[] args) throws Exception {
        System.out.println("\033[1;36m==================================================");
        System.out.println(" 🌌 INTERMED v8.0 HYPERVISOR - ULTIMATE EDITION");
        System.out.println("==================================================\033[0m");

        String agentPath = new File(InterMedLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        System.out.println("[System] Ядро: " + agentPath);
        System.out.print("[Search] Ожидание запуска Minecraft (java-runtime-gamma)");

        // PowerShell скрипт: Ищет процесс без привязки к javaw.exe и отдает результат в Base64
        String psCommand = "$procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'bootstraplauncher|client\\.main|fabricmc' }; " +
                           "foreach ($p in $procs) { " +
                           "  if ($p.CommandLine -notmatch '-javaagent') { " +
                           "    $str = $p.ProcessId.ToString() + '|||' + $p.CommandLine; " +
                           "    $bytes = [System.Text.Encoding]::UTF8.GetBytes($str); " +
                           "    [Convert]::ToBase64String($bytes); " +
                           "  } " +
                           "}";

        while (true) {
            System.out.print("."); // Анимация поиска
            
            try {
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psCommand);
                Process ps = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
                String line;
                
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        // Расшифровываем Base64 в чистейший UTF-8
                        String decoded = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
                        String[] parts = decoded.split("\\|\\|\\|", 2);
                        
                        if (parts.length == 2) {
                            String pid = parts[0];
                            String fullCmd = parts[1];

                            System.out.println("\n\033[1;33m[Target] ИГРА НАЙДЕНА! PID: " + pid + "\033[0m");
                            System.out.println("[Injector] Остановка оригинального процесса...");
                            
                            // 1. Убиваем процесс
                            Runtime.getRuntime().exec("taskkill /F /PID " + pid).waitFor();

                            // 2. Внедряем Агента сразу после .exe
                            String injection = " -javaagent:\"" + agentPath + "\" ";
                            String hackedCmd;
                            
                            int exeEnd = fullCmd.toLowerCase().indexOf(".exe");
                            if (exeEnd != -1) {
                                exeEnd += 4; // Сдвигаемся за ".exe"
                                // Если путь был в кавычках (например "C:\...\javaw.exe")
                                if (fullCmd.length() > exeEnd && fullCmd.charAt(exeEnd) == '"') {
                                    exeEnd++;
                                }
                                hackedCmd = fullCmd.substring(0, exeEnd) + injection + fullCmd.substring(exeEnd);
                            } else {
                                // Фолбэк, если .exe не найден
                                int firstSpace = fullCmd.indexOf(" ");
                                hackedCmd = fullCmd.substring(0, firstSpace) + injection + fullCmd.substring(firstSpace);
                            }

                            // 3. Создаем BAT
                            File batFile = new File(System.getProperty("java.io.tmpdir"), "intermed_boot_v8.bat");
                            Files.writeString(batFile.toPath(), "@echo off\ntitle InterMed OS Runtime\nchcp 65001 > nul\n" + hackedCmd, StandardCharsets.UTF_8);
                            
                            // 4. Отвязанный запуск
                            System.out.println("[Injector] Модификация успешна! Перезапуск с Гипервизором...");
                            new ProcessBuilder("cmd", "/c", "start", "/b", batFile.getAbsolutePath()).start();
                            
                            Thread.sleep(3000);
                            System.exit(0);
                        }
                    } catch (IllegalArgumentException e) {
                        // Строка не Base64 (игнорируем мусор от PowerShell)
                    }
                }
            } catch (Exception e) {
                // Игнорируем и пробуем снова
            }

            Thread.sleep(1500); // Пауза перед новым сканированием
        }
    }
}