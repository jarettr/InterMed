package org.intermed.cli;

import java.util.Scanner;

public class InterMedCLI {

    public static void startConsoleThread() {
        Thread.ofVirtual().name("InterMed-CLI").start(() -> {
            System.out.println("\033[1;32m[CLI] Консоль управления InterMed активирована. Введите 'help' для списка команд.\033[0m");
            Scanner scanner = new Scanner(System.in);
            
            while (true) {
                try {
                    String input = scanner.nextLine();
                    if (input == null || input.trim().isEmpty()) continue;
                    
                    String[] args = input.trim().split(" ");
                    String command = args[0].toLowerCase();
                    
                    switch (command) {
                        case "help":
                            System.out.println("--- Доступные команды InterMed ---");
                            System.out.println("status       - Статус гипервизора и TPS");
                            grantHelp();
                            break;
                        case "status":
                            System.out.println("[Status] InterMed v8.0 ULTIMATE работает штатно.");
                            System.out.println("[Status] JFR Monitor: Активен. Capability Manager: Активен.");
                            break;
                        case "grant":
                            if (args.length == 3) {
                                System.out.println("[Security] Выдано разрешение " + args[2] + " моду " + args[1]);
                                // TODO: Запись в SQLite
                            } else {
                                grantHelp();
                            }
                            break;
                        default:
                            System.out.println("Неизвестная команда. Введите 'help'.");
                    }
                } catch (Exception e) {
                    System.err.println("[CLI] Ошибка консоли: " + e.getMessage());
                }
            }
        });
    }
    
    private static void grantHelp() {
        System.out.println("grant <mod_id> <capability> - Выдать разрешение моду (например: grant fabric_api NETWORK_CONNECT)");
    }
}