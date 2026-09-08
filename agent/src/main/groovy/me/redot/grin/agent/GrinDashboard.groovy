package me.redot.grin.agent

import com.sun.management.OperatingSystemMXBean
import groovy.transform.CompileStatic
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.jline.utils.Status

import java.lang.management.*
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@CompileStatic
class GrinDashboard implements AutoCloseable {

    private static final AttributedStyle LABEL = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.GREEN)
            .bold()
    private static final AttributedStyle MUTED = AttributedStyle.DEFAULT.faint()
    private static final AttributedStyle WARNING = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.YELLOW)
            .bold()

    private final Terminal terminal
    private final GrinHeader header
    private final MemoryMXBean memory = ManagementFactory.memoryMXBean
    private final ThreadMXBean threads = ManagementFactory.threadMXBean
    private final ClassLoadingMXBean classes = ManagementFactory.classLoadingMXBean
    private final RuntimeMXBean runtime = ManagementFactory.runtimeMXBean
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.garbageCollectorMXBeans
    private final def operatingSystem = ManagementFactory.operatingSystemMXBean
    private final Status status
    private final ScheduledExecutorService updater

    GrinDashboard(Terminal terminal, GrinHeader header) {
        this.terminal = terminal
        this.header = header
        this.status = Status.getStatus(terminal)
        this.status?.setBorder(true)
        this.updater = Executors.newSingleThreadScheduledExecutor({ Runnable task ->
            Thread thread = new Thread(task, 'grin-dashboard')
            thread.daemon = true
            return thread
        })
    }

    boolean start() {
        if (status == null) {
            return false
        }

        refresh()
        if (status.size() == 0) {
            return false
        }

        updater.scheduleAtFixedRate(this::refresh, 1000, 500, TimeUnit.MILLISECONDS)
        return true
    }

    void printSnapshot(PrintWriter writer) {
        render().each { AttributedString line -> writer.println(line.toAnsi(terminal)) }
    }

    private void refresh() {
        try {
            status?.update(render())
        } catch (Throwable ignored) {
            // telemetry data must never take down the shell
        }
    }

    private List<AttributedString> render() {
        if (!header.visible) return []

        MemoryUsage heap = memory.heapMemoryUsage
        MemoryUsage nonHeap = memory.nonHeapMemoryUsage
        Map<Thread.State, Integer> states = threadStates()

        List<AttributedString> lines = [
                line { AttributedStringBuilder builder ->
                    field(builder, 'PID', processId())
                    divider(builder)
                    field(builder, 'UP', duration(runtime.uptime))
                    divider(builder)
                    builder.styled(MUTED, "$runtime.vmName - $runtime.vmVersion")
                },
                line { AttributedStringBuilder builder ->
                    field(builder, 'CPU', processCpu())
                    divider(builder)
                    field(builder, 'HEAP', memory(heap, true), usageStyle(heap))
                    divider(builder)
                    field(builder, 'NON-HEAP', memory(nonHeap, false))
                },
                line { AttributedStringBuilder builder ->
                    field(builder, 'THREADS', "${threads.threadCount} live - ${threads.daemonThreadCount} daemon - ${threads.peakThreadCount} peak")
                    divider(builder)
                    field(builder, 'STATES', stateSummary(states))
                },
                line { AttributedStringBuilder builder ->
                    field(builder, 'GC', gcSummary())
                },
                line { AttributedStringBuilder builder ->
                    field(builder, 'CLASSES', "${number(classes.loadedClassCount)} loaded - ${number(classes.totalLoadedClassCount)} total - ${number(classes.unloadedClassCount)} unloaded")
                }
        ]

        Map<String, String> custom = header.sample()
        if (!custom.isEmpty()) {
            lines.add(line { AttributedStringBuilder builder ->
                boolean first = true
                custom.each { String label, String value ->
                    if (!first) divider(builder)
                    field(builder, label.toUpperCase(Locale.ROOT), value)
                    first = false
                }
            })
        }
        return lines
    }

    private static AttributedString line(Closure<?> content) {
        AttributedStringBuilder builder = new AttributedStringBuilder()
        builder.append(' ')
        content.call(builder)
        return builder.toAttributedString()
    }

    private static void field(AttributedStringBuilder builder, String label, String value,
                              AttributedStyle valueStyle = AttributedStyle.DEFAULT) {
        builder.styled(LABEL, label)
        builder.append(' ')
        builder.styled(valueStyle, value)
    }

    private static void divider(AttributedStringBuilder builder) {
        builder.styled(MUTED, '  │  ')
    }

    private Map<Thread.State, Integer> threadStates() {
        Map<Thread.State, Integer> counts = new EnumMap<>(Thread.State)
        ThreadInfo[] info = threads.getThreadInfo(threads.allThreadIds, 0)
        info.findAll { ThreadInfo thread -> thread != null }.each { ThreadInfo thread ->
            counts.merge(thread.threadState, 1, Integer::sum)
        }
        return counts
    }

    private static String stateSummary(Map<Thread.State, Integer> states) {
        return "R ${states.getOrDefault(Thread.State.RUNNABLE, 0)} - " +
                "B ${states.getOrDefault(Thread.State.BLOCKED, 0)} - " +
                "W ${states.getOrDefault(Thread.State.WAITING, 0)} - " +
                "TW ${states.getOrDefault(Thread.State.TIMED_WAITING, 0)}"
    }

    private String processCpu() {
        if (operatingSystem instanceof OperatingSystemMXBean) {
            double load = ((OperatingSystemMXBean) operatingSystem).processCpuLoad
            if (load >= 0) {
                return String.format(Locale.ROOT, '%.1f%%', load * 100d)
            }
        }
        return 'warming up'
    }

    private String gcSummary() {
        if (collectors.isEmpty()) return 'unavailable'
        return collectors.collect { GarbageCollectorMXBean gc ->
            String count = gc.collectionCount < 0 ? '?' : number(gc.collectionCount)
            String time = gc.collectionTime < 0 ? '?' : duration(gc.collectionTime)
            "${shortGcName(gc.name)} ${count}/${time}"
        }.join(' - ')
    }

    private static String shortGcName(String name) {
        return name.replace('Generation', 'Gen').replace('Collector', '').trim()
    }

    private static String processId() {
        String name = ManagementFactory.runtimeMXBean.name
        int separator = name.indexOf('@')
        return separator > 0 ? name.substring(0, separator) : name
    }

    private static String memory(MemoryUsage usage, boolean includePercent) {
        long limit = usage.max > 0 ? usage.max : usage.committed
        String text = "${bytes(usage.used)} / ${bytes(limit)}"
        if (includePercent && limit > 0) {
            text += String.format(Locale.ROOT, ' (%.0f%%)', usage.used * 100d / limit)
        }
        return text
    }

    private static AttributedStyle usageStyle(MemoryUsage usage) {
        long limit = usage.max > 0 ? usage.max : usage.committed
        return limit > 0 && usage.used * 100d / limit >= 80d ? WARNING : AttributedStyle.DEFAULT
    }

    private static String bytes(long bytes) {
        if (bytes < 0) return '?'
        double value = bytes
        String[] units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'] as String[]
        int unit = 0
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d
            unit++
        }
        return value >= 100 || unit == 0
                ? String.format(Locale.ROOT, '%.0f %s', value, units[unit])
                : String.format(Locale.ROOT, '%.1f %s', value, units[unit])
    }

    private static String duration(long millis) {
        Duration value = Duration.ofMillis(Math.max(0, millis))
        long hours = value.toHours()
        long minutes = value.minusHours(hours).toMinutes()
        long seconds = value.minusHours(hours).minusMinutes(minutes).seconds
        if (hours > 0) return "${hours}h ${minutes}m"
        if (minutes > 0) return "${minutes}m ${seconds}s"
        return "${seconds}s"
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, '%,d', value)
    }

    @Override
    void close() {
        updater.shutdownNow()
        try {
            status?.hide()
            status?.close()
        } catch (Throwable ignored) {
        }
    }
}
