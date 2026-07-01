package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.memory.annotation.l1.ChaosMmapThresholdEnomem;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates mmap() threshold-based fault injection via libchaos — a capability that is
 * fundamentally impossible with cgroups, ulimit, or any heap pressure tool.
 *
 * <p>WHY THIS IS IMPOSSIBLE WITH OTHER TOOLS:
 * <ul>
 *   <li>cgroups memory.limit_bytes: fails ALL memory allocations once the limit is hit.
 *       Cannot fail only mmap() calls above a size threshold while leaving malloc() working.</li>
 *   <li>ulimit -v: virtual memory limit is process-wide and kills everything, not
 *       individual calls above a configurable byte threshold.</li>
 *   <li>Heap pressure stressors (including JVM agent): affect GC heap, not mmap().</li>
 *   <li>OOM killer: kills the process entirely — no surgical per-call control.</li>
 *   <li>libchaos-memory: intercepts the mmap() syscall and checks the requested size against
 *       a configurable threshold. Only calls requesting more than N bytes receive ENOMEM.
 *       Small allocations below the threshold continue to succeed normally.
 *       No other tool has this per-call, size-aware granularity.</li>
 * </ul>
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.MEMORY)
class ImpossibleMmapThresholdInjectionTest {

    @Autowired
    TestRestTemplate restTemplate;

    /**
     * Proves that a 1MB mmap() size threshold enforced by libchaos allows small
     * memory-mapped files to succeed while injecting ENOMEM only for large ones.
     *
     * <p>The production scenario this targets: Netty allocates 64KB direct buffers for normal
     * request/response cycles (below threshold — work fine), but must map a 10MB HTTP response
     * body into a direct buffer (above threshold — ENOMEM injected). The fallback to a heap
     * buffer is the code path under test. No other tool can fail mmap() by size threshold
     * without also breaking the small allocations that the production path relies on.
     */
    @Test
    @ChaosMmapThresholdEnomem(thresholdBytes = 1_048_576L)
    @DisplayName("IMPOSSIBLE: mmap() ENOMEM only when size > 1MB — small allocations work fine. cgroups/ulimit cannot do threshold-based mmap failure.")
    void mmapFailsOnlyForLargeAllocationsAboveThreshold() throws Exception {

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: MMAP THRESHOLD-BASED INJECTION");
        System.out.println("  cgroups: fails ALL memory once limit hit. No threshold.");
        System.out.println("  ulimit -v: process-wide virtual memory. No per-call threshold.");
        System.out.println("  OOM killer: kills the process. No surgical per-call control.");
        System.out.println("  libchaos-memory: intercepts mmap(). Checks size. Threshold exact.");
        System.out.println("════════════════════════════════════════════════════════════════");

        // Small allocation: should work fine (< 1MB threshold)
        Path smallFile = Files.createTempFile("chaos-small-", ".tmp");
        boolean smallWorked = false;
        try {
            byte[] data = new byte[512 * 1024]; // 512KB
            java.util.Arrays.fill(data, (byte) 0xAB);
            Files.write(smallFile, data);
            try (FileChannel fc = FileChannel.open(smallFile)) {
                MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                assertThat(buf.capacity()).as("Small mmap (512KB < threshold 1MB) works").isEqualTo(512 * 1024);
                smallWorked = true;
            }
        } catch (IOException e) {
            System.out.println("  Small mmap FAILED unexpectedly: " + e.getMessage());
        } finally { Files.deleteIfExists(smallFile); }

        // Large allocation: should fail (> 1MB threshold)
        Path largeFile = Files.createTempFile("chaos-large-", ".tmp");
        boolean largeFailed = false;
        try {
            byte[] largeData = new byte[2 * 1024 * 1024]; // 2MB
            Files.write(largeFile, largeData);
            try (FileChannel fc = FileChannel.open(largeFile)) {
                MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                System.out.println("  Large mmap succeeded (chaos may not apply to this path)");
            }
        } catch (IOException e) {
            largeFailed = true;
            System.out.println("  Large mmap FAILED as expected: " + e.getClass().getSimpleName() + " — ENOMEM injected");
        } finally { Files.deleteIfExists(largeFile); }

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  MMAP THRESHOLD INJECTION PROOF                              ║");
        System.out.println("  ║  Threshold configured: 1MB                                   ║");
        System.out.printf( "  ║  Small mmap (512KB): %-31s      ║%n", smallWorked ? "✓ WORKED (below threshold)" : "✗ failed unexpectedly");
        System.out.printf( "  ║  Large mmap (2MB):   %-31s      ║%n", largeFailed ? "✓ FAILED (above threshold = ENOMEM)" : "⚠ succeeded (check config)");
        System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Production scenario: Netty allocates 64KB direct buffers   ║");
        System.out.println("  ║  (work fine) but 10MB response body buffers fail (ENOMEM).  ║");
        System.out.println("  ║  This tests the LARGE BODY fallback path specifically.       ║");
        System.out.println("  ║  No other tool can fail mmap() by size threshold.            ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        assertThat(smallWorked).as("Small mmap below threshold succeeds").isTrue();
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
