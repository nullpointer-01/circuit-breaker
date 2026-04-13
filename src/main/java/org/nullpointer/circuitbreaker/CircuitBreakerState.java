package org.nullpointer.circuitbreaker;

/**
 * Represents the three states of the circuit breaker.
 */
public enum CircuitBreakerState {
    OPEN,
    CLOSED,
    HALF_OPEN
}
