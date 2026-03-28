package org.intermed.core.async;

/**
 * Задача для фонового выполнения в Background Preparator.
 * Сортируется по приоритету (чем меньше число, тем выше приоритет).
 */
public interface PreparatorTask extends Comparable<PreparatorTask> {
    
    int getPriority();
    void execute() throws Exception;
    String getName();

    @Override
    default int compareTo(PreparatorTask o) {
        return Integer.compare(this.getPriority(), o.getPriority());
    }
}