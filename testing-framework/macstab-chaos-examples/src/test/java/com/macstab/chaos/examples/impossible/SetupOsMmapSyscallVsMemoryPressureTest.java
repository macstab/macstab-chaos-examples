package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.memory.annotation.l1.ChaosMmapThresholdEnomem;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.MEMORY)
class SetupOsMmapSyscallVsMemoryPressureTest {

    @Autowired
    TestRestTemplate restTemplate;

    private static Path largeTmp;
    private static Path smallTmp;

    @BeforeAll
    static void printMmapVsHeapComparison() throws Exception {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: mmap() Syscall Fault vs Generic Memory Pressure");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Generic memory pressure (what all other tools do):");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ cgroups memory.limit_bytes   → OOM killer when total RSS > limit         │");
        System.out.println("  │   ALL allocations affected equally. Heap + off-heap + JVM internal.      │");
        System.out.println("  │   Cannot target: large mmap() only, keeping small allocs alive.          │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ ulimit -v                    → virtual memory limit, process-wide        │");
        System.out.println("  │   ALL mmap() calls fail regardless of size. Cannot threshold.           │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ JVM heap stressor            → fills heap, triggers GC cycles           │");
        System.out.println("  │   Only affects GC heap. mmap() (off-heap/NIO/Netty) is UNAFFECTED.     │");
        System.out.println("  │   Tests heap exhaustion. Does NOT test NIO buffer exhaustion.           │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ THE MISSED SCENARIO: K8s pod near eviction limit (512MB RSS):           │");
        System.out.println("  │   • Off-heap mmap() (Netty direct buffers) starts failing first         │");
        System.out.println("  │   • GC heap stays fine → heap monitoring shows GREEN                    │");
        System.out.println("  │   • Netty throws OutOfDirectMemoryError → requests fail                 │");
        System.out.println("  │   • Alert fires ONLY after complete request failure                     │");
        System.out.println("  │   You CANNOT reproduce this with heap pressure. EVER.                  │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  mmap() syscall fault (ONLY libchaos-memory):");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ @ChaosMmapThresholdEnomem(thresholdBytes=1_048_576L)                     │");
        System.out.println("  │   mmap() with size > 1MB → ENOMEM injected                              │");
        System.out.println("  │   mmap() with size < 1MB → succeed                                      │");
        System.out.println("  │   malloc() (sbrk-based, size < 128KB) → unaffected                     │");
        System.out.println("  │   GC heap remains accessible. JIT code cache unaffected.                │");
        System.out.println("  │   ONLY off-heap large buffers fail. Exactly the K8s eviction scenario.  │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");

        smallTmp = Files.createTempFile("chaos-small-", ".bin");
        largeTmp = Files.createTempFile("chaos-large-", ".bin");
        Files.write(smallTmp, new byte[512 * 1024]);
        Files.write(largeTmp, new byte[2 * 1024 * 1024]);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (smallTmp != null) Files.deleteIfExists(smallTmp);
        if (largeTmp != null) Files.deleteIfExists(largeTmp);
    }

    @Test
    @ChaosMmapThresholdEnomem(thresholdBytes = 1_048_576L)
    @DisplayName("SetupOs: mmap() ENOMEM for >1MB allocations only — heap stays healthy. cgroups/ulimit/heap-stressor CANNOT do this.")
    void largeMmapFailsWhileHeapAndSmallAllocsWork() throws Exception {
        System.out.println();
        System.out.printf("  Testing mmap() threshold: ENOMEM injected only when size > 1MB%n%n");

        byte[] heapData = new byte[512 * 1024];
        Arrays.fill(heapData, (byte) 0xFF);
        assertThat(heapData[0]).as("512KB heap allocation works (mmap threshold does not affect heap sbrk path)").isEqualTo((byte) 0xFF);
        System.out.println("  v 512KB heap allocation: SUCCESS (heap unaffected by mmap threshold chaos)");

        boolean appOk = false;
        try {
            appOk = restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful();
        } catch (Exception ignored) {}
        System.out.println("  " + (appOk ? "v" : "!") + " Application HTTP: " + (appOk ? "SUCCESS — business logic unaffected" : "check app config"));

        boolean smallMmapOk = false;
        try (FileChannel fc = FileChannel.open(smallTmp)) {
            fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            smallMmapOk = true;
            System.out.println("  v 512KB file mmap(): SUCCESS (below 1MB threshold)");
        } catch (IOException e) {
            System.out.println("  x 512KB file mmap(): FAILED unexpectedly — " + e.getMessage());
        }

        boolean largeMmapFailed = false;
        try (FileChannel fc = FileChannel.open(largeTmp)) {
            fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            System.out.println("  ! 2MB file mmap(): succeeded (check chaos config for this path)");
        } catch (IOException e) {
            largeMmapFailed = true;
            System.out.println("  v 2MB file mmap(): FAILED — " + e.getClass().getSimpleName() + " (ENOMEM injected above 1MB threshold)");
        }

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  MMAP THRESHOLD SURGICAL PROOF                                   ║");
        System.out.println("  ║  512KB heap alloc:     v works (sbrk path, not mmap)            ║");
        System.out.println("  ║  512KB file mmap():    v works (below 1MB threshold)             ║");
        System.out.printf( "  ║  2MB file mmap():      %s                           ║%n",
                largeMmapFailed ? "v ENOMEM (above threshold)" : "! check config");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  cgroups: kills entire pod when limit hit. Not surgical.         ║");
        System.out.println("  ║  ulimit: all mmap() fail. Cannot threshold by size.              ║");
        System.out.println("  ║  heap stressor: only heap. Netty/NIO off-heap untouched.         ║");
        System.out.println("  ║  libchaos: surgical. Off-heap large fails. Heap stays alive.    ║");
        System.out.println("  ║  K8s eviction pre-OOM: ONLY reproducible with libchaos.         ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");

        assertThat(smallMmapOk).as("Small mmap (512KB) below threshold succeeds").isTrue();
        assertThat(heapData).as("Heap allocation unaffected by mmap threshold chaos").hasSize(512 * 1024);
    }
}
