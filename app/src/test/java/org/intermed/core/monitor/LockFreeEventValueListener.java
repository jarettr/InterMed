package org.intermed.core.monitor;

@FunctionalInterface
interface LockFreeEventValueListener {
    void onValue(int value);
}
