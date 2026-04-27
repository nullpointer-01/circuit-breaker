package org.nullpointer.circuitbreaker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class CircuitBreaker<T> {
    private final Clock clock;
    private final CircuitBreakerConfig config;
    private final Supplier<T> allowedFallback;
    private final Supplier<T> deniedFallback;

    private final AtomicReference<CircuitBreakerState> state;
    private volatile long lastOpenedTimeNanos;

    private final BucketedSlidingWindow slidingWindow;

    // HALF_OPEN trial state — replaced atomically with a fresh instance on every OPEN -> HALF_OPEN transition
    private volatile HalfOpenState halfOpenState;

    // Lock object used only for HALF_OPEN completion check (rare path)
    private final Object halfOpenLock = new Object();

    public CircuitBreaker(Clock clock, CircuitBreakerConfig config,
                          Supplier<T> allowedFallback, Supplier<T> deniedFallback) {
        this.config = config;
        this.state = new AtomicReference<>(CircuitBreakerState.CLOSED);
        this.clock = clock;
        this.allowedFallback = allowedFallback;
        this.deniedFallback = deniedFallback;

        this.slidingWindow = new BucketedSlidingWindow(
                config.getNumBuckets(), config.getBucketSizeNanos(), clock);

        this.halfOpenState = HalfOpenState.exhausted();
    }

    /**
     * Determines whether the next call should be permitted.
     */
    public boolean allowExecution() {
        CircuitBreakerState currentState = state.get();

        // Allow all calls in CLOSED state
        if (CircuitBreakerState.CLOSED == currentState) {
            return true;
        }

        if (CircuitBreakerState.OPEN == currentState) {
            // Check if the wait time has elapsed to transition to HALF_OPEN
            if (clock.nanoTime() - lastOpenedTimeNanos >= config.getWaitTimeNanos()) {
                if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    halfOpenState = new HalfOpenState();
                }
            } else {
                return false;
            }
        }

        // Permit only a few calls in HALF_OPEN state
        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            HalfOpenState hs = halfOpenState; // volatile read
            int trialCalls = hs.trialCalls.getAndIncrement();
            return trialCalls < config.getPermittedHalfOpenCalls();
        }

        return true;
    }

    /**
     * Records a successful call
     */
    public void recordSuccess() {
        CircuitBreakerState currentState = state.get();

        if (CircuitBreakerState.OPEN == currentState) return;

        if (CircuitBreakerState.HALF_OPEN == currentState) {
            HalfOpenState hs = halfOpenState; // volatile read
            hs.trialSuccess.incrementAndGet();

            checkHalfOpenCompletion(hs);
            return;
        }

        // CLOSED state
        slidingWindow.recordSuccess();
        evaluateFailureRate();
    }

    /**
     * Records a failed call
     */
    public void recordError() {
        CircuitBreakerState currentState = state.get();

        if (CircuitBreakerState.OPEN == currentState) return;

        if (CircuitBreakerState.HALF_OPEN == currentState) {
            HalfOpenState hs = halfOpenState; // volatile read
            int failures = hs.trialFailures.incrementAndGet();
            int successes = hs.trialSuccess.get();
            long total = successes + failures;

            if (total > 0) {
                double rate = 100.0 * failures / total;
                if (rate >= config.getTrialFailureRate()) {
                    transitionToOpen();
                }
            }
            return;
        }

        // CLOSED state
        slidingWindow.recordError();
        evaluateFailureRate();
    }

    public boolean isOpen() {
        return CircuitBreakerState.OPEN == state.get();
    }

    public boolean isClosed() {
        return CircuitBreakerState.CLOSED == state.get();
    }

    public boolean isHalfOpen() {
        return CircuitBreakerState.HALF_OPEN == state.get();
    }

    public T getFallbackResult() {
        if (config.isFailOpenMode()) {
            return allowedFallback.get();
        }
        return deniedFallback.get();
    }

    private void evaluateFailureRate() {
        long total = slidingWindow.getTotalCount();
        if (total >= config.getMinimumCalls() && slidingWindow.getFailureRate() >= config.getFailureRate()) {
            transitionToOpen();
        }
    }

    /**
     * Uses a small synchronized block because we need to atomically read both trialSuccess and trialFailures and decide the next state.
     * This runs at most once per HALF_OPEN cycle, so it has negligible impact on throughput.
     */
    private void checkHalfOpenCompletion(HalfOpenState hs) {
        int success = hs.trialSuccess.get();
        int failures = hs.trialFailures.get();
        int totalPermittedCalls = success + failures;

        if (totalPermittedCalls >= config.getPermittedHalfOpenCalls()) {
            synchronized (halfOpenLock) {
                // Double-check under lock to avoid duplicate transitions
                if (state.get() != CircuitBreakerState.HALF_OPEN) return;

                success = hs.trialSuccess.get();
                failures = hs.trialFailures.get();
                totalPermittedCalls = success + failures;

                if (totalPermittedCalls < config.getPermittedHalfOpenCalls()) return;

                double successRate = (totalPermittedCalls > 0) ? 100.0 * success / totalPermittedCalls : 0.0;
                if (successRate >= config.getSuccessThreshold()) {
                    transitionToClosed();
                } else {
                    transitionToOpen();
                }
            }
        }
    }

    private void transitionToOpen() {
        CircuitBreakerState prev = state.getAndSet(CircuitBreakerState.OPEN);
        if (prev != CircuitBreakerState.OPEN) {
            lastOpenedTimeNanos = clock.nanoTime();
        }
    }

    private void transitionToClosed() {
        state.set(CircuitBreakerState.CLOSED);
        slidingWindow.reset();
    }

    static final class HalfOpenState {
        final AtomicInteger trialCalls;
        final AtomicInteger trialSuccess;
        final AtomicInteger trialFailures;

        private HalfOpenState() {
            this.trialCalls = new AtomicInteger(0);
            this.trialSuccess = new AtomicInteger(0);
            this.trialFailures = new AtomicInteger(0);
        }

        private HalfOpenState(int initialTrialCalls) {
            this.trialCalls = new AtomicInteger(initialTrialCalls);
            this.trialSuccess = new AtomicInteger(0);
            this.trialFailures = new AtomicInteger(0);
        }

        /**
         * Returns an instance that denies all trial calls.
         * Used as the initial value before the first HALF_OPEN cycle.
         */
        static HalfOpenState exhausted() {
            return new HalfOpenState(Integer.MAX_VALUE);
        }
    }
}