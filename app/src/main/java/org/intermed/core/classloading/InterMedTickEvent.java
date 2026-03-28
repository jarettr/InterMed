package org.intermed.core.monitoring;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Событие JFR для анализа TPS и выявления аномалий (ТЗ 3.2.6).
 */
@Name("org.intermed.ServerTick")
@Label("InterMed Server Tick")
@Description("Отслеживает длительность тика сервера для обнаружения просадок TPS.")
@Category({"InterMed", "Performance Metrics"})
public class InterMedTickEvent extends Event {
    
    @Label("Фаза Тика")
    public String tickPhase;

    @Label("Активный Мод")
    public String activeModId;
}