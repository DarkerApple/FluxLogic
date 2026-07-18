package com.fluxlogic.diag;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * "Stutter Sleuth" — per-hitch frame forensics.
 *
 * <p>Sampling profilers show <em>averages</em>; a stutter is an outlier. This
 * module instead watches every frame and, when one blows past the hitch
 * threshold, records where that specific frame's time went:
 *
 * <ul>
 *   <li><b>Decomposition</b> — each frame period splits into event-poll time
 *       (measured directly around {@code glfwPollEvents}), in-game time
 *       (tick+render+present, measured HEAD→RETURN of {@code runTick}), and
 *       <b>outside time</b> — wall-clock where the game thread simply wasn't
 *       running. Big "outside" = OS-level stall (paging, thermal throttle,
 *       scheduler, compositor), which no in-game profiler can see.</li>
 *   <li><b>In-the-act stack capture</b> — a daemon sampler watches the render
 *       thread and snapshots its stack the moment the current frame runs
 *       long, then classifies it (present/vsync, event poll, chunk build,
 *       GC, narrator, io, …).</li>
 *   <li><b>GC & memory counters</b> — GC time during the hitch frame, heap,
 *       direct buffers, OS free RAM and swap movement.</li>
 *   <li><b>CPU speed probe</b> — a fixed spin workload timed at launch and
 *       every ~30s after. If the same work takes 1.5× longer twenty minutes
 *       in, the CPU is being throttled — the smoking gun for thermal
 *       stutter that "appears some time after launch".</li>
 * </ul>
 *
 * Reports append to {@code logs/fluxlogic-sleuth.txt} (and the game log)
 * every reporting window. Pure JDK + config dependencies — fully testable
 * without Minecraft; mixins only feed it timestamps.
 */
public final class StutterSleuth {

    /** Where a hitch's time most plausibly went. */
    enum Cause {
        EVENT_POLL, PRESENT_VSYNC, GC_PAUSE, OUTSIDE_OS, CHUNKS, NARRATOR_AUDIO,
        IO, NETWORK, CLASSLOAD, PARSE, FPS_LIMITER, NATIVE_MEM, UNKNOWN
    }

    private static final long PROBE_INTERVAL_NANOS = 30_000_000_000L;
    private static final int PROBE_SPIN_ITERATIONS = 1_500_000;
    private static final int MAX_HITCH_DETAILS_PER_WINDOW = 6;

    // --- pulse state (render thread writes, sampler reads volatiles) --------
    private volatile Thread renderThread;
    private volatile long frameStartNanos;
    private volatile boolean inFrame;
    private volatile boolean inPoll;
    private volatile long pollStartVolatile;
    private volatile StackTraceElement[] capturedStack;
    private volatile boolean captureDone;

    private long lastFrameStartNanos;
    private long lastPollNanos;
    private long pollStartNanos;
    private long inFrameNanos;

    // --- window aggregation (render thread only) ----------------------------
    private long windowStartNanos;
    private int frames;
    private long frameNanosSum;
    private long worstFrameNanos;
    private int hitches;
    private final Map<Cause, Integer> causeCounts = new EnumMap<>(Cause.class);
    private final List<String> hitchDetails = new ArrayList<>();

    // --- environment counters ----------------------------------------------
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private long lastGcTimeMs;
    private long lastGcCount;
    private long windowStartSwapUsed = -1;

    // --- CPU probe ----------------------------------------------------------
    private long probeBaselineNanos = Long.MAX_VALUE;
    private int probeWarmups;
    private long lastProbeAtNanos;
    private long lastProbeNanos;

    private Thread samplerThread;
    private boolean announced;

    // ======================================================== pulses (mixins)

    /** Around the GLFW event poll (before runTick). */
    public void pollStart() {
        if (enabled()) {
            pollStartNanos = System.nanoTime();
            pollStartVolatile = pollStartNanos;
            inPoll = true;
        }
    }

    public void pollEnd() {
        inPoll = false;
        if (enabled() && pollStartNanos != 0) {
            lastPollNanos = System.nanoTime() - pollStartNanos;
            pollStartNanos = 0;
        }
    }

    /** HEAD of the game's per-frame body. */
    public void frameStart() {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (renderThread == null) {
            renderThread = Thread.currentThread();
            ensureSampler();
            announceOnce();
        }
        if (lastFrameStartNanos != 0) {
            onFramePeriod(now - lastFrameStartNanos, now);
        }
        lastFrameStartNanos = now;
        frameStartNanos = now;
        captureDone = false;
        inFrame = true;
    }

    /** RETURN of the game's per-frame body. */
    public void frameEnd() {
        if (!enabled()) {
            return;
        }
        inFrame = false;
        inFrameNanos = System.nanoTime() - frameStartNanos;
        maybeProbe();
    }

    // ===================================================== per-frame analysis

    private void onFramePeriod(long periodNanos, long now) {
        FluxConfig.Sleuth cfg = ConfigManager.get().sleuth;
        if (windowStartNanos == 0) {
            windowStartNanos = now;
            lastGcTimeMs = totalGcTimeMs();
            lastGcCount = totalGcCount();
            windowStartSwapUsed = swapUsedBytes();
        }
        frames++;
        frameNanosSum += periodNanos;
        worstFrameNanos = Math.max(worstFrameNanos, periodNanos);

        long thresholdNanos = Math.max(10, cfg.hitchThresholdMs) * 1_000_000L;
        if (periodNanos >= thresholdNanos) {
            recordHitch(periodNanos);
        }

        long windowNanos = Math.max(10, cfg.reportEverySeconds) * 1_000_000_000L;
        if (now - windowStartNanos >= windowNanos) {
            report(now);
        }
    }

    private void recordHitch(long periodNanos) {
        hitches++;

        long gcTime = totalGcTimeMs();
        long gcDeltaMs = gcTime - lastGcTimeMs;
        long gcCount = totalGcCount();
        long gcDeltaCount = gcCount - lastGcCount;
        lastGcTimeMs = gcTime;
        lastGcCount = gcCount;

        long periodMs = periodNanos / 1_000_000L;
        long pollMs = lastPollNanos / 1_000_000L;
        long inFrameMs = inFrameNanos / 1_000_000L;
        long outsideMs = Math.max(0, periodMs - inFrameMs - pollMs);

        StackTraceElement[] stack = capturedStack;
        capturedStack = null;

        Cause cause = classify(periodMs, pollMs, inFrameMs, outsideMs, gcDeltaMs, stack);
        causeCounts.merge(cause, 1, Integer::sum);

        if (hitchDetails.size() < MAX_HITCH_DETAILS_PER_WINDOW) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(String.format(Locale.ROOT,
                    "hitch %dms [%s] poll=%dms inGame=%dms OUTSIDE=%dms gc=%dms(%d)",
                    periodMs, cause, pollMs, inFrameMs, outsideMs, gcDeltaMs, gcDeltaCount));
            if (stack != null && stack.length > 0) {
                sb.append(" @ ");
                for (int i = 0; i < Math.min(4, stack.length); i++) {
                    if (i > 0) sb.append(" < ");
                    sb.append(stack[i].getClassName()
                            .substring(stack[i].getClassName().lastIndexOf('.') + 1))
                            .append('.').append(stack[i].getMethodName());
                }
            }
            hitchDetails.add(sb.toString());
        }
    }

    /** Decide the most plausible cause, preferring hard evidence over stacks. */
    private Cause classify(long periodMs, long pollMs, long inFrameMs, long outsideMs,
                           long gcDeltaMs, StackTraceElement[] stack) {
        if (gcDeltaMs >= periodMs / 2) {
            return Cause.GC_PAUSE;
        }
        if (outsideMs >= periodMs / 2) {
            return Cause.OUTSIDE_OS;
        }
        if (pollMs >= periodMs / 2) {
            return Cause.EVENT_POLL;
        }
        Cause fromStack = classifyStack(stack);
        if (fromStack != Cause.UNKNOWN) {
            return fromStack;
        }
        return inFrameMs >= periodMs / 2 ? Cause.UNKNOWN : Cause.OUTSIDE_OS;
    }

    private static Cause classifyStack(StackTraceElement[] stack) {
        if (stack == null) {
            return Cause.UNKNOWN;
        }
        for (StackTraceElement e : stack) {
            String s = (e.getClassName() + '.' + e.getMethodName()).toLowerCase(Locale.ROOT);
            if (s.contains("swapbuffer") || s.contains("present") || s.contains("acquirenext")) {
                return Cause.PRESENT_VSYNC;
            }
            if (s.contains("pollevents") || s.contains("nsevent") || s.contains("waitevents")) {
                return Cause.EVENT_POLL;
            }
            if (s.contains("limitdisplayfps") || s.contains("parknanos") || s.contains("onspinwait")) {
                return Cause.FPS_LIMITER;
            }
            if (s.contains("narrator") || s.contains("text2speech") || s.contains("speech")) {
                return Cause.NARRATOR_AUDIO;
            }
            if (s.contains("chunk") || s.contains("rendersection") || s.contains("meshdata")) {
                return Cause.CHUNKS;
            }
            if (s.contains("memoryutil") || s.contains("cleaner") || s.contains("allocat")) {
                return Cause.NATIVE_MEM;
            }
            if (s.contains("socket") || s.contains("net.poll") || s.contains("httpclient")
                    || s.contains("inetaddress")) {
                return Cause.NETWORK;
            }
            if (s.contains("mixintransformer") || s.contains("classwriter")
                    || s.contains("defineclass") || s.contains("classloader")
                    || s.contains("knotclassdelegate")) {
                return Cause.CLASSLOAD;
            }
            if (s.contains("codec") || s.contains("gson") || s.contains("json")
                    || s.contains("nbt")) {
                return Cause.PARSE;
            }
            if (s.contains("zipfile") || s.contains("jarfile") || s.contains("files.")
                    || s.contains("fileinput") || s.contains("filechannel")) {
                return Cause.IO;
            }
        }
        return Cause.UNKNOWN;
    }

    // ============================================================= reporting

    private void report(long now) {
        double seconds = (now - windowStartNanos) / 1_000_000_000.0;
        double avgMs = frames == 0 ? 0 : (frameNanosSum / 1_000_000.0) / frames;

        StringBuilder sb = new StringBuilder(768);
        sb.append(String.format(Locale.ROOT,
                "window %.0fs: frames=%d avg=%.1fms worst=%dms hitches=%d",
                seconds, frames, avgMs, worstFrameNanos / 1_000_000L, hitches));

        if (hitches > 0) {
            sb.append(" | causes: ");
            causeCounts.forEach((c, n) -> sb.append(c).append('=').append(n).append(' '));
        }

        Runtime rt = Runtime.getRuntime();
        long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long heapMaxMb = rt.maxMemory() >> 20;
        sb.append(String.format(Locale.ROOT, "| heap %d/%dMB directBuf %dMB",
                heapUsedMb, heapMaxMb, directBufferBytes() >> 20));

        long swapUsed = swapUsedBytes();
        long freeRam = freePhysicalBytes();
        if (freeRam >= 0) {
            sb.append(String.format(Locale.ROOT, " | os: freeRAM %dMB", freeRam >> 20));
        }
        if (swapUsed >= 0) {
            long delta = windowStartSwapUsed >= 0 ? swapUsed - windowStartSwapUsed : 0;
            sb.append(String.format(Locale.ROOT, " swapUsed %dMB (%+dMB)", swapUsed >> 20, delta >> 20));
        }

        if (probeBaselineNanos != Long.MAX_VALUE && lastProbeNanos > 0) {
            double slowdown = (double) lastProbeNanos / (double) probeBaselineNanos;
            sb.append(String.format(Locale.ROOT, " | cpuProbe %.2fx of launch", slowdown));
            if (slowdown >= 1.35) {
                sb.append(" << CPU IS RUNNING SLOWER THAN AT LAUNCH (thermal throttling?)");
            }
        }

        String summary = sb.toString();
        ConfigManager.LOG.info("[FluxLogic/Sleuth] {}", summary);
        List<String> lines = new ArrayList<>();
        lines.add(timestamp() + ' ' + summary);
        for (String d : hitchDetails) {
            ConfigManager.LOG.info("[FluxLogic/Sleuth]   {}", d);
            lines.add("  " + d);
        }
        appendToReportFile(lines);

        // reset window
        windowStartNanos = now;
        frames = 0;
        frameNanosSum = 0;
        worstFrameNanos = 0;
        hitches = 0;
        causeCounts.clear();
        hitchDetails.clear();
        windowStartSwapUsed = swapUsed;
    }

    private void announceOnce() {
        if (!announced) {
            announced = true;
            ConfigManager.LOG.info(
                    "[FluxLogic/Sleuth] Frame forensics active (threshold {}ms). Reports every {}s"
                            + " to the log and logs/fluxlogic-sleuth.txt",
                    ConfigManager.get().sleuth.hitchThresholdMs,
                    ConfigManager.get().sleuth.reportEverySeconds);
        }
    }

    private void appendToReportFile(List<String> lines) {
        try {
            Path path = FabricLoader.getInstance().getGameDir()
                    .resolve("logs").resolve("fluxlogic-sleuth.txt");
            Files.createDirectories(path.getParent());
            Files.write(path, (String.join(System.lineSeparator(), lines) + System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            // report file is best-effort; the log copy always exists
        }
    }

    private static String timestamp() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    // ============================================================== sampler

    private void ensureSampler() {
        if (samplerThread != null) {
            return;
        }
        samplerThread = new Thread(this::samplerLoop, "FluxLogic-Sleuth-Sampler");
        samplerThread.setDaemon(true);
        samplerThread.setPriority(Thread.NORM_PRIORITY + 1);
        samplerThread.start();
    }

    private void samplerLoop() {
        while (true) {
            LockSupport.parkNanos(2_000_000L); // 2ms cadence
            if (!enabled()) {
                continue;
            }
            Thread rt = renderThread;
            if (rt == null || captureDone) {
                continue;
            }
            // Watch whichever phase is active: the frame body or the event poll.
            long phaseStart;
            if (inFrame) {
                phaseStart = frameStartNanos;
            } else if (inPoll) {
                phaseStart = pollStartVolatile;
            } else {
                continue;
            }
            long threshold = Math.max(10, ConfigManager.get().sleuth.hitchThresholdMs) * 700_000L;
            if (System.nanoTime() - phaseStart > threshold) { // 70% of hitch threshold
                capturedStack = rt.getStackTrace();
                captureDone = true;
            }
        }
    }

    // ============================================================ CPU probe

    private void maybeProbe() {
        long now = System.nanoTime();
        if (lastProbeAtNanos != 0 && now - lastProbeAtNanos < PROBE_INTERVAL_NANOS) {
            return;
        }
        lastProbeAtNanos = now;
        long t = spinProbe();
        lastProbeNanos = t;
        // Baseline = fastest of the first few probes (least-throttled state).
        if (probeWarmups < 5) {
            probeWarmups++;
            probeBaselineNanos = Math.min(probeBaselineNanos, t);
        }
    }

    /** Fixed integer workload, ~1-2ms; JIT-resistant via data dependence. */
    private static long spinProbe() {
        long start = System.nanoTime();
        long acc = start | 1;
        for (int i = 0; i < PROBE_SPIN_ITERATIONS; i++) {
            acc = acc * 6364136223846793005L + 1442695040888963407L;
            acc ^= acc >>> 29;
        }
        long elapsed = System.nanoTime() - start;
        if (acc == 42) { // impossible; defeats dead-code elimination
            System.out.print("");
        }
        return elapsed;
    }

    // ============================================================== helpers

    private static boolean enabled() {
        return ConfigManager.get().sleuth.enabled;
    }

    private long totalGcTimeMs() {
        long t = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            long v = b.getCollectionTime();
            if (v > 0) t += v;
        }
        return t;
    }

    private long totalGcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            long v = b.getCollectionCount();
            if (v > 0) c += v;
        }
        return c;
    }

    private static long directBufferBytes() {
        try {
            long total = 0;
            for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                total += Math.max(0, b.getMemoryUsed());
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long swapUsedBytes() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalSwapSpaceSize();
            long free = os.getFreeSwapSpaceSize();
            return total < 0 || free < 0 ? -1 : total - free;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long freePhysicalBytes() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return os.getFreeMemorySize();
        } catch (Throwable t) {
            return -1;
        }
    }
}
