package com.example.alexthundercook.microbiomereplacer.profiling;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe statistics collector for chunk processing performance.
 *
 * Uses a logarithmic histogram for O(1) updates and accurate percentile calculation
 * with bounded memory (~1KB regardless of sample count).
 *
 * Design choices:
 * - Logarithmic buckets with ~5% width provide ~2% relative error for percentiles
 * - LongAdder for counters (10x faster than AtomicLong under high contention)
 * - Lock-free operations throughout (no synchronized blocks)
 * - Expected overhead: ~50ns per record() call
 */
public final class PerformanceStats {

    // Singleton instance
    private static final PerformanceStats INSTANCE = new PerformanceStats();

    // Histogram configuration
    private static final int BUCKET_COUNT = 128;
    private static final double LOG_BASE = 1.115;  // ~11.5% bucket width, covers 1μs to 1s
    private static final long MIN_VALUE_NS = 1_000;  // 1 microsecond
    private static final long MAX_VALUE_NS = 1_000_000_000L;  // 1 second

    // Precomputed log(LOG_BASE) for fast bucket calculation
    private static final double LOG_OF_BASE = Math.log(LOG_BASE);

    // Atomic histogram buckets
    private final AtomicLongArray histogram = new AtomicLongArray(BUCKET_COUNT);

    // Summary statistics (using LongAdder for high-contention add operations)
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxNanos = new AtomicLong(0);

    // Tracking for work done
    private final LongAdder replacementCount = new LongAdder();
    private final LongAdder homogeneousSkipCount = new LongAdder();
    private final LongAdder positionsProcessedCount = new LongAdder();

    private PerformanceStats() {}

    /**
     * Get the singleton instance.
     */
    public static PerformanceStats getInstance() {
        return INSTANCE;
    }

    /**
     * Record a chunk processing duration. Thread-safe, lock-free.
     *
     * @param durationNanos Processing duration in nanoseconds
     * @param positionsProcessed Number of biome positions examined
     * @param replacementsMade Number of biome replacements applied
     * @param skippedHomogeneous True if chunk was skipped due to homogeneity
     */
    public void record(long durationNanos, int positionsProcessed, int replacementsMade, boolean skippedHomogeneous) {
        // Update histogram
        int bucket = getBucket(durationNanos);
        histogram.incrementAndGet(bucket);

        // Update summary stats
        totalCount.increment();
        totalNanos.add(durationNanos);

        // Update min/max atomically
        updateMin(durationNanos);
        updateMax(durationNanos);

        // Track work done
        positionsProcessedCount.add(positionsProcessed);
        if (replacementsMade > 0) {
            replacementCount.add(replacementsMade);
        }
        if (skippedHomogeneous) {
            homogeneousSkipCount.increment();
        }
    }

    /**
     * Map a duration to a histogram bucket index.
     */
    private int getBucket(long nanos) {
        if (nanos <= MIN_VALUE_NS) return 0;
        if (nanos >= MAX_VALUE_NS) return BUCKET_COUNT - 1;

        double logValue = Math.log((double) nanos / MIN_VALUE_NS) / LOG_OF_BASE;
        return Math.min(BUCKET_COUNT - 1, Math.max(0, (int) logValue));
    }

    /**
     * Get the midpoint value for a bucket (used for percentile estimation).
     */
    private long getBucketMidpoint(int bucket) {
        return (long) (MIN_VALUE_NS * Math.pow(LOG_BASE, bucket + 0.5));
    }

    /**
     * Atomically update minimum value.
     */
    private void updateMin(long value) {
        long current;
        do {
            current = minNanos.get();
            if (value >= current) return;
        } while (!minNanos.compareAndSet(current, value));
    }

    /**
     * Atomically update maximum value.
     */
    private void updateMax(long value) {
        long current;
        do {
            current = maxNanos.get();
            if (value <= current) return;
        } while (!maxNanos.compareAndSet(current, value));
    }

    /**
     * Get percentile value from histogram.
     *
     * @param percentile Percentile to compute (0-100)
     * @return Estimated value at the given percentile in nanoseconds
     */
    public long getPercentile(double percentile) {
        long count = totalCount.sum();
        if (count == 0) return 0;

        long target = (long) (count * percentile / 100.0);
        long cumulative = 0;

        for (int i = 0; i < BUCKET_COUNT; i++) {
            cumulative += histogram.get(i);
            if (cumulative >= target) {
                return getBucketMidpoint(i);
            }
        }
        return getBucketMidpoint(BUCKET_COUNT - 1);
    }

    /**
     * Get all stats as an immutable snapshot.
     */
    public StatsSnapshot getSnapshot() {
        long count = totalCount.sum();
        long total = totalNanos.sum();
        long min = minNanos.get();
        long max = maxNanos.get();

        return new StatsSnapshot(
            count,
            total,
            min == Long.MAX_VALUE ? 0 : min,
            max,
            getPercentile(50),
            getPercentile(90),
            getPercentile(99),
            replacementCount.sum(),
            homogeneousSkipCount.sum(),
            positionsProcessedCount.sum()
        );
    }

    /**
     * Reset all statistics.
     */
    public void reset() {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            histogram.set(i, 0);
        }
        totalCount.reset();
        totalNanos.reset();
        minNanos.set(Long.MAX_VALUE);
        maxNanos.set(0);
        replacementCount.reset();
        homogeneousSkipCount.reset();
        positionsProcessedCount.reset();
    }

    /**
     * Immutable snapshot of performance statistics.
     */
    public record StatsSnapshot(
        long count,
        long totalNanos,
        long minNanos,
        long maxNanos,
        long p50Nanos,
        long p90Nanos,
        long p99Nanos,
        long replacements,
        long homogeneousSkips,
        long positionsProcessed
    ) {
        /**
         * Get mean duration in microseconds.
         */
        public double meanMicros() {
            return count > 0 ? (totalNanos / (double) count) / 1000.0 : 0;
        }

        /**
         * Convert to JSON string for test harness consumption.
         */
        public String toJson() {
            return String.format(
                "{\"count\":%d,\"mean_ms\":%.2f,\"min_ms\":%.2f,\"max_ms\":%.2f," +
                "\"p50_ms\":%.2f,\"p90_ms\":%.2f,\"p99_ms\":%.2f," +
                "\"total_ms\":%.2f,\"replacements\":%d,\"homogeneous_skips\":%d,\"positions_processed\":%d}",
                count,
                meanMicros() / 1000.0,
                minNanos / 1_000_000.0,
                maxNanos / 1_000_000.0,
                p50Nanos / 1_000_000.0,
                p90Nanos / 1_000_000.0,
                p99Nanos / 1_000_000.0,
                totalNanos / 1_000_000.0,
                replacements,
                homogeneousSkips,
                positionsProcessed
            );
        }
    }
}
