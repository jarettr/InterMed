package org.intermed.core.monitor;

public class ObservabilityMonitor {
    private static final double ALPHA = 0.2;
    private static double ewmaTickTime = 50.0;
    private static long lastTickStart = 0;

    public static void onTickStart() {
        lastTickStart = System.currentTimeMillis();
    }

    public static void onTickEnd(String currentModContext) {
        if (lastTickStart == 0) return;
        long tickDelta = System.currentTimeMillis() - lastTickStart;
        ewmaTickTime = (ALPHA * tickDelta) + ((1.0 - ALPHA) * ewmaTickTime);

        if (ewmaTickTime > 55.0) {
            triggerAnomalyDetection(ewmaTickTime, currentModContext);
        }
    }

    private static void triggerAnomalyDetection(double avgTickTime, String suspectModId) {
        System.out.printf("\033[1;31m[JFR Monitor] TPS DROP! Current: %.2f TPS\033[0m\n", 1000.0 / avgTickTime);
        
        try {
            HeavyModTickEvent event = new HeavyModTickEvent();
            event.modId = (suspectModId != null) ? suspectModId : "unknown";
            event.durationMs = avgTickTime;
            event.ewmaTickTime = ewmaTickTime;
            event.commit(); // Это отправит данные в JFR
        } catch (Throwable t) {
            // Если JFR не поддерживается, просто игнорируем
        }
    }
}