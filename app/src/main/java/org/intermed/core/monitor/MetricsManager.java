package org.intermed.core.monitor;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * TZ Req 14: Адаптивный мониторинг производительности.
 */
public class MetricsManager {

    @Name("org.intermed.LoadEvent")
    @Label("InterMed Load Step")
    public static class LoadEvent extends Event {
        @Label("Step Name")
        public String stepName;
        @Label("Duration MS")
        public long duration;
    }

    public static void recordStep(String name, long ms) {
        LoadEvent event = new LoadEvent();
        event.stepName = name;
        event.duration = ms;
        event.commit();
        
        // EWMA логика: если загрузка шага > 500мс, бьем тревогу
        if (ms > 500) {
            System.err.println("\033[1;31m[Metrics] Performance Anomaly detected in: " + name + " (" + ms + "ms)\033[0m");
        }
    }
}