package org.intermed.security;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.intermed.security.FileSecurityAdvice;
import org.intermed.security.NetworkSecurityAdvice;
import org.intermed.security.ReflectionSecurityAdvice;
import org.intermed.security.UnsafeSecurityAdvice;

import java.lang.instrument.Instrumentation;

/**
 * Главный Java Agent платформы InterMed v8.0 (ТЗ 3.2.5).
 * Инструментирует системные классы JVM для обеспечения безопасности до загрузки самой игры.
 */
public class InterMedAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("\033[1;36m[InterMed Agent] Инициализация системы безопасности ByteBuddy...\033[0m");

        new AgentBuilder.Default()
            // Отключаем стандартный игнор java.*, чтобы можно было перехватывать системные вызовы
            .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
            // 1. Перехват файловых операций (FileInputStream)
            .type(ElementMatchers.nameEndsWith("FileInputStream"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(FileSecurityAdvice.class).on(ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(java.io.File.class))))
            )
            // 2. Перехват сетевых соединений (Socket)
            .type(ElementMatchers.named("java.net.Socket"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(NetworkSecurityAdvice.class).on(ElementMatchers.named("connect")))
            )
            // 3. Перехват рефлексии (AccessibleObject.setAccessible)
            .type(ElementMatchers.named("java.lang.reflect.AccessibleObject"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(ReflectionSecurityAdvice.class).on(ElementMatchers.named("setAccessible")))
            )
            // 4. Перехват манипуляций с памятью (sun.misc.Unsafe.allocateInstance)
            .type(ElementMatchers.named("sun.misc.Unsafe"))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(Advice.to(UnsafeSecurityAdvice.class).on(ElementMatchers.named("allocateInstance")))
            )
            .installOn(inst);
            
        System.out.println("\033[1;32m[InterMed Agent] Перехватчики успешно установлены.\033[0m");
    }
}