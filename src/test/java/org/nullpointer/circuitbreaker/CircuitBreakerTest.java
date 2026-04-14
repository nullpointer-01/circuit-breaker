package org.nullpointer.circuitbreaker;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private CircuitBreaker<Boolean> createBreaker(MutableClock clock, CircuitBreakerConfig config) {
        return new CircuitBreaker<>(clock, config, () -> true, () -> false);
    }

    @Test
    void closedStateAllowsExecution() {
        MutableClock clock = new MutableClock();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, CircuitBreakerConfig.builder().build());

        assertTrue(breaker.isClosed());
        assertFalse(breaker.isOpen());
        assertFalse(breaker.isHalfOpen());
        assertTrue(breaker.allowExecution());
    }

    @Test
    void failClosedModeReturnsDeniedFallback() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .mode(CircuitBreakerMode.FAIL_CLOSED)
            .windowSize(1)
            .waitTime(2, TimeUnit.SECONDS)
            .failureRate(0.0)
            .trialFailureRate(50.0)
            .permittedHalfOpenCalls(20)
            .minimumCalls(1)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();

        assertTrue(breaker.isOpen());
        assertFalse(breaker.isClosed());
        assertFalse(breaker.allowExecution());
        assertFalse(breaker.getFallbackResult());
    }

    @Test
    void failOpenModeReturnsAllowedFallback() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .mode(CircuitBreakerMode.FAIL_OPEN)
            .windowSize(1)
            .waitTime(2, TimeUnit.SECONDS)
            .failureRate(0.0)
            .trialFailureRate(50.0)
            .permittedHalfOpenCalls(20)
            .minimumCalls(1)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();

        assertTrue(breaker.isOpen());
        assertFalse(breaker.isClosed());
        assertFalse(breaker.allowExecution());
        assertTrue(breaker.getFallbackResult());
    }

    @Test
    void openTransitionsToHalfOpenAfterWaitAndClosesOnSuccess() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .mode(CircuitBreakerMode.FAIL_CLOSED)
            .windowSize(1)
            .waitTime(2, TimeUnit.SECONDS)
            .failureRate(0.0)
            .trialFailureRate(10)
            .permittedHalfOpenCalls(5)
            .minimumCalls(1)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());

        clock.advanceNanos(TimeUnit.SECONDS.toNanos(3));
        assertTrue(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
        assertFalse(breaker.isOpen());

        breaker.recordSuccess();
        assertTrue(breaker.isHalfOpen());
        assertTrue(breaker.allowExecution());
    }

    @Test
    void opensOnlyAfterMinimumCallsReached() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .windowSize(10)
            .failureRate(50.0)
            .minimumCalls(3)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        breaker.recordError();

        assertTrue(breaker.isClosed());
        assertTrue(breaker.allowExecution());

        breaker.recordSuccess();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());
    }

    @Test
    void opensWhenFailureRateThresholdIsBreached() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .windowSize(4)
            .failureRate(50.0)
            .minimumCalls(4)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        breaker.recordError();
        breaker.recordSuccess();
        breaker.recordSuccess();

        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());
    }

    @Test
    void slidingWindowOverwriteUsesLatestWindowOnly() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .windowSize(3)
            .failureRate(50.0)
            .minimumCalls(3)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        breaker.recordSuccess();
        breaker.recordSuccess();
        assertTrue(breaker.isClosed());
        assertTrue(breaker.allowExecution());

        breaker.recordSuccess();
        assertTrue(breaker.isClosed());
        assertTrue(breaker.allowExecution());

        breaker.recordError();
        assertTrue(breaker.isClosed());
        assertTrue(breaker.allowExecution());
    }

    @Test
    void halfOpenPermittedCallsLimitIsEnforced() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .windowSize(1)
            .waitTime(2, TimeUnit.SECONDS)
            .failureRate(0.0)
            .permittedHalfOpenCalls(2)
            .minimumCalls(1)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());

        clock.advanceNanos(TimeUnit.SECONDS.toNanos(3));
        assertTrue(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
        assertTrue(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
        assertFalse(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
    }

    @Test
    void halfOpenFailureRateThresholdReopensBreaker() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .windowSize(1)
            .waitTime(2, TimeUnit.SECONDS)
            .failureRate(0.0)
            .trialFailureRate(50.0)
            .permittedHalfOpenCalls(5)
            .minimumCalls(1)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());

        clock.advanceNanos(TimeUnit.SECONDS.toNanos(3));
        assertTrue(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
        breaker.recordError();

        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());
    }

    @Test
    void halfOpenClosesOnlyAfterRequiredSuccessfulTrialCalls() {
        MutableClock clock = new MutableClock();
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
            .mode(CircuitBreakerMode.FAIL_CLOSED)
            .windowSize(1)
            .waitTime(2, TimeUnit.SECONDS)
            .failureRate(0.0)
            .trialFailureRate(100.0)
            .permittedHalfOpenCalls(2)
            .minimumCalls(1)
            .build();
        CircuitBreaker<Boolean> breaker = createBreaker(clock, config);

        breaker.recordError();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowExecution());

        clock.advanceNanos(TimeUnit.SECONDS.toNanos(3));
        assertTrue(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
        breaker.recordSuccess();

        assertTrue(breaker.allowExecution());
        assertTrue(breaker.isHalfOpen());
        breaker.recordSuccess();

        assertTrue(breaker.isClosed());
        assertFalse(breaker.isHalfOpen());
        assertTrue(breaker.allowExecution());
    }

    private static final class MutableClock implements Clock {
        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advanceNanos(long deltaNanos) {
            nanos += deltaNanos;
        }
    }
}
