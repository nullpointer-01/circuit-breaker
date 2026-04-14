# Circuit Breaker

A lightweight, thread-safe, zero-dependency circuit breaker library for Java 17+.

## Features

- **Count-based sliding window** for failure rate detection
- **Three states**: CLOSED → OPEN → HALF_OPEN → CLOSED
- **Configurable**: window size, failure rate threshold, wait time, half-open trial calls
- **Two failure modes**: FAIL_OPEN (optimistic) and FAIL_CLOSED (conservative)
- **Generic fallback**: `CircuitBreaker<T>` works with any result type via `Supplier<T>`
- **Thread-safe**: all state transitions are synchronized

## Usage

### Basic Example

```java
import org.nullpointer.circuitbreaker.*;

// Configure
CircuitBreakerConfig config = CircuitBreakerConfig.builder()
    .mode(CircuitBreakerMode.FAIL_OPEN)
    .windowSize(10)
    .failureRate(50.0)
    .minimumCalls(5)
    .waitTime(30, TimeUnit.SECONDS)
    .permittedHalfOpenCalls(3)
    .build();

// Create with typed fallback suppliers
CircuitBreaker<Response> cb = new CircuitBreaker<>(
    Clock.systemClock(),
    config,
    () -> Response.ok(),      // fallback for FAIL_OPEN mode
    () -> Response.denied()   // fallback for FAIL_CLOSED mode
);

// Use
if (cb.allowExecution()) {
    try {
        Response result = callDownstreamService();
        cb.recordSuccess();
        return result;
    } catch (Exception e) {
        cb.recordError();
        return cb.getFallbackResult();
    }
} else {
    return cb.getFallbackResult();
}
```

### Default Configuration

Use `CircuitBreakerFactory` for quick defaults:

```java
CircuitBreakerConfig config = CircuitBreakerFactory.defaultCircuitBreakerConfig();
// Defaults: FAIL_OPEN, window=10, failureRate=50%, waitTime=30s, halfOpenCalls=10, minCalls=5
```

## Configuration Options

| Parameter | Default | Description |
|-----------|---------|-------------|
| `mode` | `FAIL_OPEN` | Fallback behaviour when open (`FAIL_OPEN` or `FAIL_CLOSED`) |
| `windowSize` | `10` | Size of the sliding window for tracking outcomes |
| `failureRate` | `50.0` | Failure rate threshold (%) to trip the breaker |
| `minimumCalls` | `5` | Minimum calls before failure rate is evaluated |
| `waitTime` | `30s` | Time to wait in OPEN state before transitioning to HALF_OPEN |
| `permittedHalfOpenCalls` | `10` | Number of trial calls allowed in HALF_OPEN state |
| `trialFailureRate` | `50.0` | Failure rate threshold (%) during HALF_OPEN to re-open |

## State Transitions

```
    ┌──────────────────────────────────────────────┐
    │                                              │
    ▼                                              │
 CLOSED ──(failure rate ≥ threshold)──► OPEN       │
                                         │         │
                                    (wait time)    │
                                         │         │
                                         ▼         │
                                      HALF_OPEN    │
                                       │    │      │
                          (trials OK)──┘    └──(trials fail)
                               │                   │
                               ▼                   │
                            CLOSED                 │
                               └───────────────────┘
```

## Testing

```bash
mvn test
```

## License

MIT
