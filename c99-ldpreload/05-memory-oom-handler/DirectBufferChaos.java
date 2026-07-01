import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Demonstrates application resilience to sporadic mmap ENOMEM failures
 * injected by libchaos-memory via LD_PRELOAD.
 *
 * ByteBuffer.allocateDirect() ultimately calls mmap(MAP_ANONYMOUS) inside
 * the JVM.  With 1% ENOMEM probability, approximately 1 in 100 allocations
 * will throw OutOfMemoryError.  This app catches each OOM, logs it, and
 * continues — proving that correct error handling keeps the service alive.
 *
 * Run:
 *   javac DirectBufferChaos.java
 *   LD_PRELOAD=libchaos-memory-glibc-amd64.so \
 *   CHAOS_MEMORY_CONF=/chaos/.chaos-memory.conf \
 *   java -Xmx256m DirectBufferChaos
 */
public class DirectBufferChaos {

    // ---------------------------------------------------------------- //
    // Configuration
    // ---------------------------------------------------------------- //
    private static final int    TOTAL_ALLOCS    = 2_000;
    private static final int    BUFFER_SIZE_KB  = 64;   // 64 KiB per buffer
    private static final int    MAX_LIVE_BUFFERS = 20;  // keep pool bounded
    private static final int    REPORT_EVERY    = 100;  // print every N allocs

    // ---------------------------------------------------------------- //
    // Counters
    // ---------------------------------------------------------------- //
    private static long allocOk    = 0;
    private static long allocOom   = 0;
    private static long allocOther = 0;

    // ---------------------------------------------------------------- //
    // Entry point
    // ---------------------------------------------------------------- //
    public static void main(String[] args) {
        System.out.printf("DirectBufferChaos — %d allocations of %d KiB%n",
                TOTAL_ALLOCS, BUFFER_SIZE_KB);
        System.out.println("libchaos-memory injecting 1% ENOMEM via LD_PRELOAD");
        System.out.println();
        System.out.printf("%-8s  %-8s  %-8s  %-10s  %-12s%n",
                "alloc#", "ok", "OOM", "OOM%", "live_bufs");
        System.out.println("-".repeat(56));

        // Circular pool to keep some buffers alive (realistic pressure)
        Deque<ByteBuffer> pool = new ArrayDeque<>();

        for (int i = 1; i <= TOTAL_ALLOCS; i++) {
            ByteBuffer buf = tryAllocate(BUFFER_SIZE_KB * 1024);

            if (buf != null) {
                // Write some data to make the mapping real
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int b = 0; b < 64; b++) {
                    buf.put(b, (byte) rng.nextInt());
                }
                pool.addLast(buf);
            }

            // Evict oldest buffer when pool is full
            while (pool.size() > MAX_LIVE_BUFFERS) {
                pool.pollFirst(); // eligible for GC / Cleaner release
            }

            if (i % REPORT_EVERY == 0) {
                double oomPct = 100.0 * allocOom / i;
                System.out.printf("%-8d  %-8d  %-8d  %-10.2f  %-12d%n",
                        i, allocOk, allocOom, oomPct, pool.size());
            }
        }

        // Final summary
        System.out.println();
        System.out.println("=".repeat(56));
        System.out.println("FINAL SUMMARY");
        System.out.println("=".repeat(56));
        System.out.printf("  Total allocations  : %d%n", TOTAL_ALLOCS);
        System.out.printf("  Successful         : %d  (%.1f%%)%n",
                allocOk, 100.0 * allocOk / TOTAL_ALLOCS);
        System.out.printf("  OutOfMemoryError   : %d  (%.1f%%)%n",
                allocOom, 100.0 * allocOom / TOTAL_ALLOCS);
        System.out.printf("  Other errors       : %d%n", allocOther);
        System.out.printf("  Buffer size        : %d KiB%n", BUFFER_SIZE_KB);
        System.out.printf("  Total data touched : %d MiB%n",
                allocOk * BUFFER_SIZE_KB / 1024);
        System.out.println();
        if (allocOom > 0) {
            System.out.println("  RESULT: Application survived ENOMEM — OOM handling is correct.");
        } else {
            System.out.println("  RESULT: No OOM injected in this run (chaos config may not be active).");
        }
        System.out.println("=".repeat(56));
    }

    // ---------------------------------------------------------------- //
    // Allocation wrapper — catches and classifies errors                //
    // ---------------------------------------------------------------- //
    private static ByteBuffer tryAllocate(int bytes) {
        try {
            ByteBuffer buf = ByteBuffer.allocateDirect(bytes);
            allocOk++;
            return buf;
        } catch (OutOfMemoryError oom) {
            // libchaos-memory returned ENOMEM from mmap(); the JVM wraps
            // that as OutOfMemoryError("Direct buffer memory").
            allocOom++;
            System.err.printf("  [OOM #%d] %s%n", allocOom, oom.getMessage());
            return null;
        } catch (Exception e) {
            allocOther++;
            System.err.printf("  [ERR] %s: %s%n", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
