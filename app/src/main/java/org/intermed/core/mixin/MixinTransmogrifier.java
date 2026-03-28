package org.intermed.core.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class MixinTransmogrifier {

    private static boolean isTransmogrifierActive = false;

    /**
     * Вызывается из BackgroundPreparator'а для подмены логики MixinProcessor'а
     */
    public static void bootstrapTransmogrification() {
        if (isTransmogrifierActive) return;
        
        System.out.println("\033[1;35m[Transmogrifier] Intercepting Mixin subsystem control...\033[0m");

        try {
            // This is a delicate operation. We're replacing the core Mixin service provider.
            // This must be done extremely early, before MixinEnvironment fully initializes.
            
            Class<?> serviceClass = Class.forName("org.spongepowered.asm.service.MixinService");
            Field serviceField = serviceClass.getDeclaredField("service");
            serviceField.setAccessible(true);

            // Remove the 'final' modifier from the field
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(serviceField, serviceField.getModifiers() & ~Modifier.FINAL);

            // Inject our custom service
            serviceField.set(null, new InterMedMixinService());
            
            // Now, we need to force Mixin to re-initialize with our service.
            // We can do this by resetting the environment and triggering a re-selection.
            Class<?> mixinEnvClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            
            // Reset the current environment
            Field currentEnvField = mixinEnvClass.getDeclaredField("currentEnvironment");
            currentEnvField.setAccessible(true);
            currentEnvField.set(null, null);

            // Trigger re-selection of the environment, which will use our service
            Method selectMethod = mixinEnvClass.getDeclaredMethod("select");
            selectMethod.setAccessible(true);
            Object environment = selectMethod.invoke(null);
            
            System.out.println("[Transmogrifier] MixinEnvironment re-initialized: " + environment.toString());

            isTransmogrifierActive = true;
            System.out.println("\033[1;32m[Transmogrifier] Mixin Transmogrification successful. InterMed service is in control.\033[0m");

        } catch (ClassNotFoundException e) {
            System.out.println("[Transmogrifier] Mixin classes not found. Assuming lazy loading scenario.");
        } catch (Exception e) {
            System.err.println("\033[1;31m[Transmogrifier] CRITICAL FAILURE during Mixin interception: " + e.getMessage() + "\033[0m");
            e.printStackTrace();
        }
    }
}