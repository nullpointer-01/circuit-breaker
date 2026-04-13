package org.nullpointer.circuitbreaker;

@FunctionalInterface
public interface Clock {
    long nanoTime();

    static Clock systemClock() {
        return System::nanoTime;
    }
}
