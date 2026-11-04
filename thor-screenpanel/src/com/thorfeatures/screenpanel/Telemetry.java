package com.thorfeatures.screenpanel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The telemetry tab's reading layer: what to read, how to read it in one
 * command, and how to turn the reply into numbers.
 *
 * All of it is pure text in, pure text out, so the whole thing is exercised on
 * the host with no device - which matters more here than for the panel's other
 * features, because a gauge that silently shows a wrong number is worse than
 * one that shows nothing.
 *
 * <h3>Two reads, not one per value</h3>
 *
 * Discovery runs once when the panel opens and resolves every path. A tick
 * then reads those fixed paths. Both are a single {@code grep} across a file
 * list, because the cost here is forks, not bytes: this project measured
 * {@code cat}-per-directory at 87 ms against 13 ms for the shell's {@code
 * read} builtin over the same nine directories, and {@code screenblank status}
 * went from 482 ms to 179 ms with byte-identical output purely by removing
 * forks. One {@code grep} over N files is one fork for any N.
 *
 * <h3>Resolve by type, never by index</h3>
 *
 * Thermal zone and cooling device numbers are assigned at boot and move. The
 * fan's cooling device was 34 on one boot and 35 on another on this same
 * build, and it read as {@code cooling_device34} when this was written.
 * Nothing here ever names an index: discovery reads every {@code type} file
 * and the paths are derived from whichever ones matched.
 *
 * <h3>What is not reachable on this build</h3>
 *
 * <b>There is no tachometer.</b> The fan is the upstream {@code pwm-fan}
 * driver bound at {@code pm8550@1/pwm-fan@0}, and the only attribute it
 * exposes is {@code pwm1} - {@code find /sys -name 'fan*'} returns nothing
 * outside the device tree, so there is no {@code fan1_input} and no RPM
 * anywhere. The fan gauge therefore shows duty and the governor's cooling
 * state, which is everything the hardware offers.
 *
 * <b>The GPU may not be readable at all.</b> {@code /sys/class/kgsl/} is
 * {@code Permission denied} under SELinux in the context a command string
 * runs in, despite uid 0. Discovery probes several candidate paths and the
 * gauge reports itself unavailable when none answers, rather than showing a
 * zero that looks like an idle GPU.
 */
public final class Telemetry {

    /**
     * How often a tick runs while the telemetry tab is in front.
     *
     * One second, chosen rather than inherited. The constraint is not the
     * panel's own cost - it is that measuring must not change what is being
     * measured. A `fanctl selftest` that re-ran discovery per item took over
     * fourteen minutes and warmed the SoC enough to spin the fan up by
     * itself, so the test polluted its own subject. A tick here is two forks
     * over an already-open shell, and at 1 Hz that is a duty cycle small
     * enough that the gauges cannot be reading their own heat.
     *
     * One second is also the slowest rate that still reads as live: CPU
     * frequency moves faster than this and will alias, but temperature, fan
     * duty, battery draw and memory all move far slower, and a frequency
     * sampled at 1 Hz is still the right order of magnitude. Going faster
     * costs forks for a number that is already approximate.
     *
     * Ticks run only while the tab is visible and the activity is resumed -
     * see {@link #shouldPoll}. The panel is a transient control, so the real
     * bound on cost is how long it is actually on screen.
     */
    public static final long TICK_MS = 1000;

    /** Battery readings live at fixed paths; no discovery needed. */
    static final String BATTERY = "/sys/class/power_supply/battery";

    /**
     * Candidate GPU utilisation paths, in the order they are preferred.
     *
     * <b>Utilisation and not frequency, which is measured rather than
     * assumed.</b> The obvious gauge is {@code devfreq/cur_freq}, and on this
     * device it is useless: it reads 680000000 and stays there. Its own
     * {@code trans_stat} is unambiguous - 2,693,080 ms accumulated at
     * 680 MHz, exactly 0 ms at each of the other eight available steps, and
     * three recorded transitions in the whole uptime - while
     * {@code gpu_busy_percentage} over the same moments read 0-4%. The
     * frequency node reports the ceiling, not the clock.
     *
     * So a frequency gauge here would be a flat "680 MHz" that never moves
     * and always looks like a GPU at full tilt. That is the same failure as
     * measuring the panel's own frame rate with a Choreographer: always
     * plausible, never right. Utilisation moves, so utilisation is the gauge.
     *
     * Several candidates rather than one because the brief's path did not
     * resolve the way it was expected to. Probing costs nothing extra: they
     * go into the same discovery {@code grep} as everything else, and a path
     * that cannot be read simply produces no line. {@code gpu_busy_percentage}
     * reads "2 %" and {@code gpu_load} reads a bare "4"; the parser takes
     * either.
     */
    static final String[] GPU_BUSY_CANDIDATES = {
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
    };

    private Telemetry() {}

    // --- what may enter a command -------------------------------------------

    /**
     * Whether a discovered path is safe to put in a command.
     *
     * Discovery learns directory names from the device, and those names then
     * appear in the next command, so this is the one place where device
     * output reaches a shell string. The panel's existing invariant is that
     * no shell metacharacter can reach an argv, and this keeps it: a path
     * must be an absolute {@code /sys} or {@code /proc} path of ordinary
     * path characters. A colon is excluded along with the metacharacters,
     * because the reply format is {@code path:value} and a colon in a path
     * would make that ambiguous.
     *
     * Not a security boundary - sysfs is root-owned - but a correctness one.
     * A path this rejects is a path this code did not expect, and dropping a
     * gauge is the right response to that.
     */
    public static boolean isSafePath(String path) {
        if (path == null || path.isEmpty()) return false;
        if (!path.startsWith("/sys/") && !path.startsWith("/proc/")) return false;
        if (path.contains("..") || path.contains("//")) return false;
        return path.matches("[A-Za-z0-9_,.@/-]+");
    }

    // --- grouping temperatures ----------------------------------------------

    /**
     * Which gauge a thermal zone's type belongs to, or null to ignore it.
     *
     * Matched on substrings of the type rather than exact names on purpose.
     * This device reports many zones per subsystem ({@code cpu-0-0-usr},
     * {@code cpu-1-0-usr}, ...) and the fan alone is bound to 32 trips across
     * them, so an exact list would be long, boot-specific and wrong the first
     * time a kernel renamed one. A prefix rule survives that.
     *
     * Order matters. {@code usb-therm} and the skin sensor are checked before
     * the broad {@code cpu}/{@code gpu} rules so a specific sensor is never
     * swallowed by a general one.
     *
     * {@code usb-therm} gets its own gauge because this project has already
     * been misled by it: it is heated mainly by charge current, not SoC load,
     * and a validation session once produced a confident plateau that was
     * near a best case because no PD had been negotiated.
     *
     * <b>"Board" is {@code xo-therm}, and it is not called "Skin" on
     * purpose.</b> This device has 60 thermal zones and <em>none</em> of them
     * is a skin sensor: there is no {@code skin} in any type and no
     * {@code quiet-therm}, which is what the usual Qualcomm layout would have
     * offered. {@code xo-therm} is the crystal-oscillator thermistor on the
     * board, which is the nearest thing available and is a reasonable proxy
     * for what the chassis feels - but it is a board sensor, so calling the
     * row "Skin" would claim a measurement this hardware does not make. The
     * {@code skin} and {@code quiet-therm} rules are kept below as a fallback
     * for a build that does have one, ahead of {@code xo-therm} because a real
     * skin sensor would be the better reading.
     *
     * Checked against the device 2026-11-04: 18 zones match the CPU rule
     * ({@code cpuss-0..3}, {@code cpu-0-0..2}, {@code cpu-1-0..10}), 8 match
     * GPU ({@code gpuss-0..7}), and {@code battery}, {@code usb-therm} and
     * {@code xo-therm} each match exactly one. Note {@code vbat} does not
     * match the battery rule - it has one "t" - and a bare {@code usb} zone
     * exists but is not {@code usb-therm}, so neither is picked up by
     * accident.
     */
    static String groupFor(String type) {
        if (type == null) return null;
        String t = type.toLowerCase(Locale.ROOT);
        if (t.equals("usb-therm"))                         return "USB";
        if (t.contains("batt"))                            return "Battery";
        if (t.contains("skin") || t.equals("quiet-therm")) return "Board";
        if (t.equals("xo-therm"))                          return "Board";
        if (t.contains("gpu"))                             return "GPU";
        if (t.startsWith("cpu"))                           return "CPU";
        return null;
    }

    /** The gauge order on screen. Groups with no zones are left out. */
    static final String[] GROUP_ORDER = { "CPU", "GPU", "Board", "USB", "Battery" };

    // --- resolved paths -----------------------------------------------------

    /** One cpufreq policy: a cluster, not a core. */
    public static final class Policy {
        public final String name;       // "policy0"
        public final String cpus;       // "0 1 2", as the kernel reports it
        public final String curPath;
        Policy(String name, String cpus, String curPath) {
            this.name = name; this.cpus = cpus; this.curPath = curPath;
        }
        @Override public String toString() { return name + "[" + cpus + "]"; }
    }

    /**
     * Everything discovery resolved. Built once per panel open.
     *
     * Three cpufreq policies on this device rather than eight cores
     * ({@code policy0} = cpu0-2, {@code policy3} = cpu3-6, {@code policy7} =
     * cpu7), so a tick reads three frequencies and not eight. Cores inside a
     * cluster share a clock, so the extra five reads would be duplicates.
     */
    public static final class Layout {
        public final List<Policy> policies = new ArrayList<>();
        /** Group label to that group's zone temp paths. Insertion-ordered. */
        public final Map<String, List<String>> zones = new LinkedHashMap<>();
        public String fanPwmPath;
        public String fanStatePath;
        public String fanMaxStatePath;
        public String gpuBusyPath;
        /**
         * The fastest clock any cluster can reach, in kHz, for the CPU bar.
         *
         * The ceiling is the prime core's, not a cluster average: the gauge
         * shows whichever cluster is fastest right now, so the scale has to be
         * the fastest any of them goes or the bar would peg. Read from
         * cpuinfo_max_freq, which is the hardware limit, rather than
         * scaling_max_freq, which a thermal driver lowers at runtime - a bar
         * whose top moves while you watch it is not a bar.
         */
        public long cpuMaxKhzCeiling = ABSENT;

        public boolean hasFan() { return fanPwmPath != null; }
        public boolean hasGpu() { return gpuBusyPath != null; }

        /**
         * True when discovery resolved nothing at all.
         *
         * Which means root was refused or the shell did not answer, not that
         * the device has no sensors - every one of these paths exists on this
         * hardware. The card says so rather than drawing a grid of em dashes,
         * because this project has shipped a control that looked alive and did
         * nothing before and it cost real time to diagnose.
         */
        public boolean isEmpty() {
            return policies.isEmpty() && zones.isEmpty() && !hasFan();
        }

        @Override public String toString() {
            return "policies=" + policies + " zones=" + zones.keySet()
                 + " fan=" + (hasFan() ? "yes" : "no")
                 + " gpu=" + (hasGpu() ? "yes" : "no");
        }
    }

    // --- discovery ----------------------------------------------------------

    /**
     * One command that resolves every path the gauges need.
     *
     * {@code grep -H .} over a file list prints {@code path:value} for each
     * readable file and nothing for the rest, which makes presence and value
     * the same answer - that is how the GPU candidates are probed. {@code -H}
     * is explicit rather than relied upon: with a glob that matches a single
     * file, grep would otherwise omit the prefix and every line would parse
     * as a value with no path.
     */
    public static String discoverCommand() {
        StringBuilder sb = new StringBuilder("grep -H . ");
        sb.append("/sys/class/thermal/thermal_zone*/type ");
        sb.append("/sys/class/thermal/cooling_device*/type ");
        sb.append("/sys/devices/system/cpu/cpufreq/policy*/affected_cpus ");
        sb.append("/sys/devices/system/cpu/cpufreq/policy*/cpuinfo_max_freq ");
        for (String p : GPU_BUSY_CANDIDATES) sb.append(p).append(' ');
        sb.append("2>/dev/null");
        return sb.toString();
    }

    /** Resolve the discovery reply into paths. Unreadable things stay null. */
    public static Layout parseDiscovery(String out) {
        Layout l = new Layout();
        if (out == null) return l;

        for (String raw : out.split("\n")) {
            String line = raw.trim();
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String path  = line.substring(0, i);
            String value = line.substring(i + 1).trim();
            if (!isSafePath(path)) continue;

            if (path.startsWith("/sys/class/thermal/thermal_zone") && path.endsWith("/type")) {
                String group = groupFor(value);
                if (group == null) continue;
                String temp = path.substring(0, path.length() - "/type".length()) + "/temp";
                l.zones.computeIfAbsent(group, k -> new ArrayList<>()).add(temp);

            } else if (path.startsWith("/sys/class/thermal/cooling_device") && path.endsWith("/type")) {
                // The fan, found by its driver's name and never by its index.
                if (!value.equals("pwm-fan")) continue;
                String dir = path.substring(0, path.length() - "/type".length());
                l.fanStatePath    = dir + "/cur_state";
                l.fanMaxStatePath = dir + "/max_state";
                // The duty node is the hwmon the same driver registers. It is
                // the one fan path that is not discovered, because hwmon0 has
                // no name attribute to match on - it reads empty on this
                // build - so there is nothing to resolve it by.
                l.fanPwmPath      = "/sys/class/hwmon/hwmon0/pwm1";

            } else if (path.endsWith("/affected_cpus")) {
                String dir = path.substring(0, path.length() - "/affected_cpus".length());
                int s = dir.lastIndexOf('/');
                l.policies.add(new Policy(dir.substring(s + 1), value, dir + "/scaling_cur_freq"));

            } else if (path.endsWith("/cpuinfo_max_freq")) {
                long max = parseLong(value);
                if (max != ABSENT && (l.cpuMaxKhzCeiling == ABSENT || max > l.cpuMaxKhzCeiling)) {
                    l.cpuMaxKhzCeiling = max;
                }

            } else {
                // First candidate that answered wins; the array is in
                // preference order.
                for (String c : GPU_BUSY_CANDIDATES) {
                    if (path.equals(c) && l.gpuBusyPath == null) l.gpuBusyPath = path;
                }
            }
        }
        // Order the temperature gauges predictably regardless of zone order.
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (String g : GROUP_ORDER) if (l.zones.containsKey(g)) ordered.put(g, l.zones.get(g));
        for (Map.Entry<String, List<String>> e : l.zones.entrySet()) ordered.putIfAbsent(e.getKey(), e.getValue());
        l.zones.clear();
        l.zones.putAll(ordered);
        return l;
    }

    // --- one tick -----------------------------------------------------------

    /**
     * The per-tick command: one {@code grep} over every resolved path,
     * {@code /proc/meminfo} included.
     *
     * <b>One fork, not two, because it was measured.</b> The first version
     * used a second {@code grep} with a narrow pattern for meminfo, to avoid
     * dragging fifty irrelevant lines through every tick. On the device
     * 2026-11-04, over ten iterations each:
     *
     * <pre>
     *   two greps, meminfo filtered    41 ms
     *   one grep, meminfo whole        26 ms
     *   one grep, one file (the floor) 13 ms
     * </pre>
     *
     * So a fork costs 13 ms and the extra lines cost nothing measurable - the
     * whole 41-path read is 26 ms, of which 13 is the fork itself and about
     * 9 ms is 29 thermal sensors. Filtering meminfo was paying 15 ms to avoid
     * 48 short lines, which is the wrong way round. It also costs no code:
     * {@link #parseTick} already collects every meminfo line and picks the two
     * it wants, so matching them all changes nothing above this method.
     *
     * The exit status is deliberately not load-bearing. A partly readable
     * tick yields partly filled {@link Readings}, and each gauge decides for
     * itself whether it has a number - this project's standing rule that a
     * value is verified by reading it, not by an exit code.
     */
    public static String tickCommand(Layout l) {
        List<String> paths = new ArrayList<>();
        for (Policy p : l.policies) paths.add(p.curPath);
        for (List<String> g : l.zones.values()) paths.addAll(g);
        if (l.fanPwmPath != null)      paths.add(l.fanPwmPath);
        if (l.fanStatePath != null)    paths.add(l.fanStatePath);
        if (l.fanMaxStatePath != null) paths.add(l.fanMaxStatePath);
        if (l.gpuBusyPath != null)     paths.add(l.gpuBusyPath);
        paths.add(BATTERY + "/current_now");
        paths.add(BATTERY + "/voltage_now");
        paths.add(BATTERY + "/status");
        paths.add(BATTERY + "/capacity");
        paths.add("/proc/meminfo");
        // /proc/stat for CPU utilisation. It joins the same grep rather than
        // getting a filtered one of its own: on this device the whole file is
        // 1417 bytes over 16 lines, longest line 851 chars, so carrying it
        // whole costs nothing measurable where a second fork costs 13 ms.
        paths.add("/proc/stat");

        StringBuilder sb = new StringBuilder("grep -H . ");
        for (String p : paths) {
            if (!isSafePath(p)) continue;
            sb.append(p).append(' ');
        }
        sb.append("2>/dev/null");
        return sb.toString();
    }

    /** A value that was not readable this tick. */
    public static final long ABSENT = Long.MIN_VALUE;

    /** One tick's numbers. Anything unreadable stays {@link #ABSENT}. */
    public static final class Readings {
        /** Per-policy current frequency in kHz, aligned with Layout.policies. */
        public final List<Long> cpuKhz = new ArrayList<>();
        /** Group label to that group's hottest zone, in milli-degrees C. */
        public final Map<String, Long> tempMilliC = new LinkedHashMap<>();
        public long gpuBusyPct = ABSENT;
        public long fanPwm = ABSENT;
        public long fanState = ABSENT;
        public long fanMaxState = ABSENT;
        public long batCurrentUa = ABSENT;
        public long batVoltageUv = ABSENT;
        public long batCapacity = ABSENT;
        public String batStatus;
        public long memTotalKb = ABSENT;
        public long memAvailKb = ABSENT;
        /** Raw jiffy counters, kept so the next tick can take a delta. */
        public long cpuTotalJiffies = ABSENT;
        public long cpuIdleJiffies = ABSENT;
        /**
         * CPU utilisation 0-100, or {@link #ABSENT} on the first tick.
         *
         * Absent rather than zero on the first tick, and the distinction
         * matters: a utilisation is a rate, so it does not exist until there
         * are two samples to subtract. Showing 0% would claim an idle CPU at
         * exactly the moment the panel has no idea.
         */
        public long cpuUtilPct = ABSENT;

        /**
         * The fastest cluster right now, which is what a CPU gauge means.
         *
         * <b>This reading observes a CPU that is awake to serve it.</b> The
         * {@code cat} performing the read is itself the load, so by the time
         * the value is produced the governor has already ramped that cluster -
         * six consecutive samples on an idle device returned byte-identical
         * frequencies. That is an observer effect, not a dead node, and the
         * residency counters prove it: {@code stats/time_in_state} on
         * 2026-11-04 showed policy0 had visited 14 of its 16 states over
         * 24,682 transitions, policy3 19 of 20, and policy7 19 of 21. The node
         * moves; it just cannot be caught idle from the inside.
         *
         * Worth contrasting with the GPU, where the same technique gave the
         * opposite answer - {@code trans_stat} showed 0 ms at eight of nine
         * steps, which is why the GPU gauge is a utilisation and this one is a
         * frequency. The lesson is the technique, not either result: ask the
         * residency counter whether a node is alive before trusting it.
         */
        public long cpuMaxKhz() {
            long max = ABSENT;
            for (Long v : cpuKhz) if (v != null && v != ABSENT && (max == ABSENT || v > max)) max = v;
            return max;
        }
    }

    /** As {@link #parseTick(String, Layout, Readings)} with no previous tick. */
    public static Readings parseTick(String out, Layout layout) {
        return parseTick(out, layout, null);
    }

    /**
     * Parse a tick reply.
     *
     * Values are matched against the paths {@code layout} asked for rather
     * than pattern-matched out of the text, so a line this code did not
     * request cannot land in a gauge.
     *
     * @param previous the preceding tick's readings, or null on the first.
     *                 CPU utilisation is a delta of jiffy counters and cannot
     *                 be computed from one sample, so it is the one reading
     *                 that depends on history.
     */
    public static Readings parseTick(String out, Layout layout, Readings previous) {
        Readings r = new Readings();
        Map<String, String> got = new LinkedHashMap<>();
        List<String> meminfo = new ArrayList<>();
        List<String> procStat = new ArrayList<>();

        if (out != null) {
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                int i = line.indexOf(':');
                if (i <= 0) continue;
                String path  = line.substring(0, i);
                String value = line.substring(i + 1).trim();
                if (path.equals("/proc/meminfo")) meminfo.add(value);
                else if (path.equals("/proc/stat")) procStat.add(value);
                else got.put(path, value);
            }
        }

        for (Policy p : layout.policies) r.cpuKhz.add(parseLong(got.get(p.curPath)));

        for (Map.Entry<String, List<String>> e : layout.zones.entrySet()) {
            long hottest = ABSENT;
            for (String path : e.getValue()) {
                long v = parseLong(got.get(path));
                if (v == ABSENT) continue;
                v = normalizeTempMilliC(v);
                if (hottest == ABSENT || v > hottest) hottest = v;
            }
            r.tempMilliC.put(e.getKey(), hottest);
        }

        if (layout.gpuBusyPath != null)     r.gpuBusyPct  = parseBusyPercent(got.get(layout.gpuBusyPath));
        if (layout.fanPwmPath != null)      r.fanPwm      = parseLong(got.get(layout.fanPwmPath));
        if (layout.fanStatePath != null)    r.fanState    = parseLong(got.get(layout.fanStatePath));
        if (layout.fanMaxStatePath != null) r.fanMaxState = parseLong(got.get(layout.fanMaxStatePath));

        r.batCurrentUa = parseLong(got.get(BATTERY + "/current_now"));
        r.batVoltageUv = parseLong(got.get(BATTERY + "/voltage_now"));
        r.batCapacity  = parseLong(got.get(BATTERY + "/capacity"));
        r.batStatus    = got.get(BATTERY + "/status");

        for (String m : meminfo) {
            // "MemTotal:  16303292 kB"
            int c = m.indexOf(':');
            if (c <= 0) continue;
            String key = m.substring(0, c).trim();
            String val = m.substring(c + 1).trim().replace("kB", "").trim();
            if (key.equals("MemTotal"))          r.memTotalKb = parseLong(val);
            else if (key.equals("MemAvailable")) r.memAvailKb = parseLong(val);
        }

        for (String line : procStat) {
            long[] t = parseCpuTimes(line);
            if (t == null) continue;
            r.cpuTotalJiffies = t[0];
            r.cpuIdleJiffies  = t[1];
            break;
        }
        if (previous != null) {
            r.cpuUtilPct = cpuUtilPercent(
                    previous.cpuTotalJiffies, previous.cpuIdleJiffies,
                    r.cpuTotalJiffies, r.cpuIdleJiffies);
        }
        return r;
    }

    /**
     * Total and idle jiffies out of {@code /proc/stat}'s aggregate line, or
     * null if this is not that line.
     *
     * Only the {@code cpu} summary line, never {@code cpu0}..{@code cpu7}.
     * The per-core lines are in the same file and start with the same three
     * letters, so the match is on the whole first token - a {@code
     * startsWith("cpu")} here would take whichever core happened to be listed
     * first and report one core's load as the whole chip's.
     *
     * Fields after the label are user, nice, system, idle, iowait, irq,
     * softirq, steal, guest, guest_nice. Total sums the first eight; guest and
     * guest_nice are already counted inside user and nice, so adding them
     * would inflate the denominator. Idle counts iowait as idle, because a CPU
     * waiting on storage is not doing work the user can see.
     */
    static long[] parseCpuTimes(String line) {
        if (line == null) return null;
        String[] f = line.trim().split("\\s+");
        if (f.length < 5 || !f[0].equals("cpu")) return null;
        long total = 0;
        for (int i = 1; i < f.length && i <= 8; i++) {
            long v = parseLong(f[i]);
            if (v == ABSENT) return null;
            total += v;
        }
        long idle = parseLong(f[4]);                       // idle
        long iowait = f.length > 5 ? parseLong(f[5]) : 0;  // iowait
        if (idle == ABSENT) return null;
        if (iowait == ABSENT) iowait = 0;
        return new long[] { total, idle + iowait };
    }

    /**
     * Utilisation between two jiffy snapshots, 0-100, or {@link #ABSENT}.
     *
     * Returns ABSENT rather than 0 when the window is empty or the counters
     * went backwards. An empty window happens if two ticks land inside the
     * same jiffy, and counters go backwards only if something has gone wrong -
     * either way the honest answer is "no reading", not "idle".
     */
    static long cpuUtilPercent(long prevTotal, long prevIdle, long curTotal, long curIdle) {
        if (prevTotal == ABSENT || prevIdle == ABSENT
                || curTotal == ABSENT || curIdle == ABSENT) return ABSENT;
        long dTotal = curTotal - prevTotal;
        long dIdle  = curIdle - prevIdle;
        if (dTotal <= 0 || dIdle < 0 || dIdle > dTotal) return ABSENT;
        long pct = Math.round((dTotal - dIdle) * 100.0 / dTotal);
        return Math.max(0, Math.min(100, pct));
    }

    static long parseLong(String s) {
        if (s == null) return ABSENT;
        String t = s.trim();
        if (t.isEmpty()) return ABSENT;
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return ABSENT; }
    }

    /** {@code gpu_busy_percentage} reads like "42 %". Take the number. */
    static long parseBusyPercent(String s) {
        if (s == null) return ABSENT;
        String t = s.trim();
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        t = t.replace("%", "").trim();
        return parseLong(t);
    }

    /**
     * Thermal zones on this device report milli-degrees C, but not every
     * driver does, so a value too small to be milli-degrees is taken as whole
     * degrees.
     *
     * The guard matters because the two are indistinguishable from the number
     * alone and the failure is silent: 45 would render as 0 C, which reads as
     * a working sensor on an improbably cold phone rather than as a bug. The
     * threshold is 1000, so anything at or above 1 C in milli-degrees is left
     * alone and a bare "45" becomes 45000.
     */
    static long normalizeTempMilliC(long raw) {
        long abs = Math.abs(raw);
        if (abs != 0 && abs < 1000) return raw * 1000;
        return raw;
    }

    // --- formatting ---------------------------------------------------------

    /** What a gauge shows when this tick had no number for it. */
    public static final String NONE = "—";   // em dash

    /** CPU frequency: kHz in, "2.02 GHz" or "864 MHz" out. */
    public static String formatCpuFreq(long khz) {
        if (khz == ABSENT || khz < 0) return NONE;
        long mhz = Math.round(khz / 1000.0);
        if (mhz >= 1000) return String.format(Locale.ROOT, "%.2f GHz", mhz / 1000.0);
        return mhz + " MHz";
    }

    /** A utilisation reading: "42%". */
    public static String formatPercent(long pct) {
        if (pct == ABSENT || pct < 0) return NONE;
        return Math.min(pct, 100) + "%";
    }

    /**
     * The CPU row: utilisation and clock together, "58%  2.71 GHz".
     *
     * Both, in one row, on the pattern the Fan row already set ("38%  3/8").
     * The alternative considered was a tap that toggled between them, and it
     * was rejected: it hides half the reading behind a gesture nothing
     * advertises, it would leave the CPU row tappable while the GPU row next
     * to it cannot be (there is no live GPU clock to toggle to), and the gauge
     * rows are deliberately styled as readings rather than controls - a 6dp
     * bar against the brightness slider's 40dp - so making one of them
     * respond to touch argues with the thing the styling is there to say.
     *
     * Utilisation leads because it is the number that answers "is it working
     * hard", and because it lines up with the GPU row directly beneath. The
     * clock follows because on a handheld it is the number that says whether
     * the chip is being allowed to.
     *
     * On the first tick there is no utilisation yet - it is a delta - so the
     * row shows the clock alone rather than an em dash beside it.
     */
    public static String formatCpuRow(long utilPct, long khz) {
        String clock = formatCpuFreq(khz);
        if (utilPct == ABSENT) return clock;
        return formatPercent(utilPct) + "  " + clock;
    }

    /** Temperature: milli-degrees in, "45 C" out. */
    public static String formatTemp(long milliC) {
        if (milliC == ABSENT) return NONE;
        return Math.round(milliC / 1000.0) + "°C";
    }

    /**
     * Fan: duty and the governor's cooling state, because there is no RPM.
     *
     * Both are shown rather than one, because they answer different
     * questions and this project has been bitten by the gap between them. A
     * trip change can read back correctly while the fan stays latched at its
     * old state for minutes - these zones have {@code polling-delay = <0>}
     * and only re-evaluate on a sensor event - so duty is what the hardware
     * is doing and state is what the governor currently believes.
     */
    public static String formatFan(long pwm, long state, long maxState) {
        if (pwm == ABSENT && state == ABSENT) return NONE;
        StringBuilder sb = new StringBuilder();
        if (pwm != ABSENT) {
            if (pwm <= 0) sb.append("off");
            else sb.append(Math.round(pwm * 100.0 / 255.0)).append('%');
        } else {
            sb.append(NONE);
        }
        if (state != ABSENT && maxState != ABSENT && maxState > 0) {
            sb.append("  ").append(state).append('/').append(maxState);
        }
        return sb.toString();
    }

    /**
     * Battery power: microamps and microvolts in, "+4.52 W" out.
     *
     * <h4>Direction: {@code status} first, then the current's own sign</h4>
     *
     * {@code status} is preferred because it says "Charging" or "Discharging"
     * in words and cannot be misread. But it is frequently neither: on this
     * device, plugged in at 85%, it sits at <b>"Not charging"</b> for long
     * stretches while the charger holds. Taking the sign from {@code status}
     * alone therefore left a common case rendering as a bare "6.05 W" with no
     * direction at all - observed 2026-11-04, and worse than being wrong,
     * because the user cannot tell which way 6 W is going.
     *
     * So an undecided {@code status} falls through to the sign of {@code
     * current_now}, and both directions of that register have now been
     * observed on this device. Charging read {@code +229619}. Then, under a
     * CPU load with the charger holding at 85% and {@code status} still
     * saying "Not charging", it read negative at 6.05 W - the battery
     * supplementing a charger that was supplying less than the load wanted.
     * Positive is into the battery, negative is out of it, confirmed from
     * both sides rather than assumed from one.
     *
     * Zero current gets no sign, and needs none: "0.00 W" is unambiguous.
     */
    public static String formatWatts(long uA, long uV, String status) {
        if (uA == ABSENT || uV == ABSENT) return NONE;
        double w = Math.abs(uA / 1e6) * (uV / 1e6);
        String sign = "";
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("charging"))         sign = "+";
        else if (s.startsWith("discharging")) sign = "−";   // true minus
        else if (uA > 0)                      sign = "+";
        else if (uA < 0)                      sign = "−";
        return String.format(Locale.ROOT, "%s%.2f W", sign, w);
    }

    /** Memory: "6.1 / 15.5 GiB" used of total. */
    public static String formatRam(long totalKb, long availKb) {
        if (totalKb == ABSENT || availKb == ABSENT || totalKb <= 0) return NONE;
        double usedGib  = (totalKb - availKb) / 1048576.0;
        double totalGib = totalKb / 1048576.0;
        return String.format(Locale.ROOT, "%.1f / %.1f GiB", usedGib, totalGib);
    }

    // --- bar fill -----------------------------------------------------------

    /** A bar position 0-100, or -1 when there is no number to show. */
    public static int percent(long value, long max) {
        if (value == ABSENT || max == ABSENT || max <= 0) return -1;
        long pct = Math.round(value * 100.0 / max);
        return (int) Math.max(0, Math.min(100, pct));
    }

    /**
     * Temperature as a bar, mapped over a fixed window rather than a sensor's
     * own range.
     *
     * 30 C reads empty and 90 C reads full. The window is fixed because a
     * thermal zone's {@code crit} trip is not a useful top of scale - several
     * of these zones trip well above anything the chassis ever reaches, so a
     * bar scaled to them would sit near zero forever and show nothing.
     */
    public static int tempPercent(long milliC) {
        if (milliC == ABSENT) return -1;
        double c = milliC / 1000.0;
        double pct = (c - 30.0) * 100.0 / 60.0;
        return (int) Math.max(0, Math.min(100, Math.round(pct)));
    }

    // --- which gauges, in what order ----------------------------------------

    /** Key prefix for a temperature gauge; the suffix is the group label. */
    public static final String TEMP = "temp:";

    /**
     * The four ring gauges, in display order, for a resolved layout.
     *
     * These four because they are what stock's own overlay ringed, and
     * because they are the readings that move while a game runs - the ones
     * worth watching out of the corner of an eye. A node discovery did not
     * find costs its ring rather than drawing a zero, so an absent reading and
     * a genuine zero never look alike. The GPU is the case that matters: its
     * sysfs directory is readable only through the panel's own transport.
     */
    public static List<String> ringKeys(Layout l, boolean fpsAvailable) {
        List<String> keys = new ArrayList<>();
        // FPS leads when it works: it is the number a person opens this tab
        // to see, and the only one that is about the game rather than the
        // machine. It is absent far more often than not - it needs a focused
        // app on the top screen that is actually drawing - so it earns its
        // place by being there, not by holding a slot that reads blank.
        if (fpsAvailable)          keys.add("fps");
        if (!l.policies.isEmpty()) keys.add("cpu");
        if (l.hasGpu())            keys.add("gpu");
        keys.add("battery");
        keys.add("ram");
        return keys;
    }

    /**
     * The rings grouped into the cards they are drawn on, in display order.
     *
     * Three groups, and the grouping is the same argument the sizes make,
     * said a second way. A gap between cards reads as a change of subject,
     * so the subjects are: what the <em>game</em> is doing, what the
     * <em>chip</em> is doing, and what the <em>device</em> is spending.
     * Putting CPU and GPU on one card is what says "compare these two to each
     * other"; putting power and memory on another says "and these are
     * something else".
     *
     * Empty groups drop out rather than leaving a card with nothing on it, so
     * a build with no readable GPU gets a narrower middle card and a build
     * with nothing drawing on the top screen gets no first card at all.
     */
    public static List<List<String>> ringGroups(Layout l, boolean fpsAvailable) {
        List<List<String>> groups = new ArrayList<>();
        List<String> all = ringKeys(l, fpsAvailable);
        String[][] wanted = {
            { "fps" },
            { "cpu", "gpu" },
            { "battery", "ram" },
        };
        for (String[] group : wanted) {
            List<String> present = new ArrayList<>();
            for (String key : group) if (all.contains(key)) present.add(key);
            if (!present.isEmpty()) groups.add(present);
        }
        return groups;
    }

    /**
     * The diameter of the largest ring on show, in dp.
     *
     * Every ring card is given this height rather than wrapping its own
     * contents, so the cards in the row share one baseline and one bottom
     * edge. Left to wrap, a card holding small rings finishes higher than the
     * one beside it and the row reads as ragged rather than as a set.
     */
    public static int maxRingSizeDp(List<List<String>> groups) {
        int max = RING_SMALL;
        for (List<String> group : groups) {
            for (String key : group) max = Math.max(max, ringSizeDp(key));
        }
        return max;
    }

    /** The share of the row's width a card of rings takes. */
    public static float groupWeight(List<String> group) {
        float w = 0f;
        for (String key : group) w += ringWeight(key);
        return w;
    }

    /**
     * The supporting readings, shown as bars under the rings.
     *
     * Temperatures and fan duty change slowly enough to be checked rather than
     * watched, so they get the quieter shape and a section of their own.
     * Keeping two shapes is also what makes the hierarchy readable at a
     * glance: a circle is a headline, a bar is a detail.
     *
     * Fan duty sits with the temperatures rather than alone. It is not a
     * temperature, but it is the device's answer to them - reading 94 C next
     * to 8/8 says the cooling is already doing everything it can, which
     * neither number says by itself.
     *
     * {@code usb-therm} is here for a reason beyond completeness. Its 60 C
     * quiet trip in fanctl is still reasoned rather than measured - no log has
     * caught it above 42 C - and it is driven mainly by charge current rather
     * than SoC load, so a live reading during a real charge-plus-game session
     * is the evidence that question has been waiting for.
     */
    public static List<String> barKeys(Layout l) {
        List<String> keys = new ArrayList<>();
        if (l.zones.containsKey("CPU"))   keys.add(TEMP + "CPU");
        if (l.zones.containsKey("Board")) keys.add(TEMP + "Board");
        if (l.zones.containsKey("USB"))   keys.add(TEMP + "USB");
        if (l.hasFan())                   keys.add("fan");
        return keys;
    }

    /** The label for a bar's reading. */
    public static String gaugeLabel(String key) {
        if (key == null) return "";
        if (key.startsWith(TEMP)) {
            String g = key.substring(TEMP.length());
            // The group names double as labels, except CPU and GPU, which
            // would otherwise collide with the rings of the same name above.
            if (g.equals("CPU") || g.equals("GPU")) return g + " temp";
            return g;
        }
        switch (key) {
            case "fps":     return "FPS";
            case "cpu":     return "CPU";
            case "gpu":     return "GPU";
            case "fan":     return "Fan";
            case "battery": return "Power";
            case "ram":     return "RAM";
            default:        return key;
        }
    }

    /**
     * Ring diameters, in dp, by how much the reading matters.
     *
     * Three sizes rather than one because five equal circles give five equal
     * claims on the eye, and these are not equal. The row is read while
     * something else is happening on the other screen, so the question each
     * gauge answers decides its size:
     *
     * <ul>
     * <li><b>Large - FPS.</b> The only reading here that is about the game
     *     rather than the machine, and the one the tab gets opened for.</li>
     * <li><b>Medium - CPU and GPU.</b> What is being asked of the chip, and
     *     the pair you compare against each other when a game drops frames.
     *     Keeping them the same size is what makes that comparison fair.</li>
     * <li><b>Small - power and memory.</b> Real readings, but ones you check
     *     rather than watch; neither changes what you would do in the next
     *     few seconds.</li>
     * </ul>
     *
     * A cell's width is weighted by the same number, so each stays close to
     * square and the gaps between rings stay even.
     */
    public static final int RING_LARGE  = 132;
    public static final int RING_MEDIUM = 112;
    public static final int RING_SMALL  = 88;

    /** How big a ring should be drawn, in dp. */
    public static int ringSizeDp(String key) {
        if ("fps".equals(key)) return RING_LARGE;
        if ("cpu".equals(key) || "gpu".equals(key)) return RING_MEDIUM;
        return RING_SMALL;
    }

    /**
     * The share of the row's width a ring takes.
     *
     * Derived from its diameter rather than chosen separately, so the two can
     * never drift into a cell that is much wider than the circle it holds.
     */
    public static float ringWeight(String key) {
        return ringSizeDp(key) / 100f;
    }

    /** The short name drawn at the top of a ring. */
    public static String ringLabel(String key) {
        if (key == null) return "";
        switch (key) {
            case "fps":     return "FPS";
            case "cpu":     return "CPU";
            case "gpu":     return "GPU";
            case "battery": return "PWR";
            case "ram":     return "RAM";
            default:        return gaugeLabel(key);
        }
    }

    /**
     * Full scale for the power ring, in watts.
     *
     * <b>A display convention, not a hardware limit.</b> There is no honest
     * ceiling for battery power - the draw depends on what is plugged in, and
     * this device cannot even negotiate PD while adb holds the USB-C port - so
     * the arc is scaled to a round number that covers what this handheld
     * actually does: a few watts idle, 6 W seen under a shell load, and
     * charging in the teens. The printed figure is exact; only the arc is
     * approximate, and it saturates rather than misleading past full.
     */
    public static final double PWR_FULL_SCALE_W = 25.0;

    /** The large figure in the middle of a ring, with no unit. */
    public static String ringBig(String key, Readings r, Fps.Sample fps) {
        if (key == null) return NONE;
        if (key.equals("fps")) {
            return fps == null || fps.fps == ABSENT
                   ? NONE : String.valueOf(Math.round(fps.fps));
        }
        if (r == null) return NONE;
        switch (key) {
            case "cpu":     return cpuFreqValue(r.cpuMaxKhz());
            case "gpu":     return r.gpuBusyPct == ABSENT
                                   ? NONE : String.valueOf(Math.min(r.gpuBusyPct, 100));
            case "battery": return wattsValue(r.batCurrentUa, r.batVoltageUv, r.batStatus);
            case "ram":     return r.memTotalKb == ABSENT || r.memAvailKb == ABSENT
                                   ? NONE
                                   : String.format(Locale.ROOT, "%.1f",
                                       (r.memTotalKb - r.memAvailKb) / 1048576.0);
            default:        return gaugeValue(key, r, null);
        }
    }

    /**
     * The small line under a ring's figure: its unit, and anything the figure
     * alone does not say.
     *
     * The CPU's carries the load percentage beside the unit, so the ring keeps
     * both numbers the row used to show. Which is the clock and which is the
     * load stays fixed whatever the readings are - the large figure is always
     * the clock - so the gauge never changes meaning between ticks, including
     * the first one, where the load is not computable yet.
     */
    public static String ringSub(String key, Readings r, Fps.Sample fps) {
        if (key == null) return "";
        if (key.equals("fps")) {
            // The panel's own rate, so the figure above reads as a fraction of
            // what the screen can actually show: 119 of 120 is a game keeping
            // up, and 119 alone does not say that.
            if (fps == null || fps.refreshHz <= 0) return "fps";
            return "/ " + Math.round(fps.refreshHz);
        }
        if (r == null) return "";
        switch (key) {
            case "cpu": {
                String unit = cpuFreqUnit(r.cpuMaxKhz());
                if (r.cpuUtilPct == ABSENT) return unit;
                return unit + "  " + formatPercent(r.cpuUtilPct);
            }
            case "gpu":     return "%";
            case "battery": return "W";
            case "ram":     return r.memTotalKb == ABSENT
                                   ? "GB"
                                   : String.format(Locale.ROOT, "/ %.1f GB",
                                       r.memTotalKb / 1048576.0);
            default:        return "";
        }
    }

    /** The clock without its unit: "2.71", or "864". */
    static String cpuFreqValue(long khz) {
        if (khz == ABSENT || khz < 0) return NONE;
        long mhz = Math.round(khz / 1000.0);
        if (mhz >= 1000) return String.format(Locale.ROOT, "%.2f", mhz / 1000.0);
        return String.valueOf(mhz);
    }

    /** The unit that goes with {@link #cpuFreqValue}. */
    static String cpuFreqUnit(long khz) {
        if (khz == ABSENT || khz < 0) return "";
        return Math.round(khz / 1000.0) >= 1000 ? "GHz" : "MHz";
    }

    /**
     * Signed watts to one decimal and no unit, for a ring's middle.
     *
     * One decimal rather than the row's two: the figure sits inside a circle
     * about 40dp across, and a fourth digit there is smaller than it is
     * useful. The sign rule is the row's, unchanged.
     */
    static String wattsValue(long uA, long uV, String status) {
        if (uA == ABSENT || uV == ABSENT) return NONE;
        double w = Math.abs(uA / 1e6) * (uV / 1e6);
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        String sign = "";
        if (s.startsWith("charging"))         sign = "+";
        else if (s.startsWith("discharging")) sign = "−";
        else if (uA > 0)                      sign = "+";
        else if (uA < 0)                      sign = "−";
        return String.format(Locale.ROOT, "%s%.1f", sign, w);
    }

    /**
     * How much of a ring to fill, 0..1, or -1 for no reading.
     *
     * Everything but power reuses {@link #gaugePercent}, so a ring and the bar
     * it replaced cannot disagree. Power is the exception because it is the
     * one reading with no ceiling to divide by; it gets
     * {@link #PWR_FULL_SCALE_W} and saturates there.
     */
    public static float ringFraction(String key, Readings r, Layout l, Fps.Sample fps) {
        if ("fps".equals(key)) {
            int pct = Fps.percent(fps);
            return pct < 0 ? -1f : pct / 100f;
        }
        if ("battery".equals(key)) {
            if (r == null || r.batCurrentUa == ABSENT || r.batVoltageUv == ABSENT) return -1f;
            double w = Math.abs(r.batCurrentUa / 1e6) * (r.batVoltageUv / 1e6);
            return (float) Math.max(0.0, Math.min(1.0, w / PWR_FULL_SCALE_W));
        }
        int pct = gaugePercent(key, r, null, l);
        return pct < 0 ? -1f : pct / 100f;
    }

    /** The text a gauge shows for one tick's readings. */
    public static String gaugeValue(String key, Readings r, Fps.Sample fps) {
        if (key == null || r == null) return NONE;
        if (key.startsWith(TEMP)) {
            Long v = r.tempMilliC.get(key.substring(TEMP.length()));
            return formatTemp(v == null ? ABSENT : v);
        }
        switch (key) {
            case "fps":     return Fps.format(fps);
            case "cpu":     return formatCpuRow(r.cpuUtilPct, r.cpuMaxKhz());
            case "gpu":     return formatPercent(r.gpuBusyPct);
            case "fan":     return formatFan(r.fanPwm, r.fanState, r.fanMaxState);
            case "battery": return formatWatts(r.batCurrentUa, r.batVoltageUv, r.batStatus);
            case "ram":     return formatRam(r.memTotalKb, r.memAvailKb);
            default:        return NONE;
        }
    }

    /**
     * The bar fill 0-100 for a gauge, or -1 for no bar.
     *
     * The CPU's ceiling comes from {@code cpuinfo_max_freq} per policy. The
     * GPU's is simply 100, because the reading is a utilisation and not a
     * frequency. Battery power gets no bar at all: there is no honest ceiling
     * for it - the draw depends on what is plugged in, and this device cannot
     * even negotiate PD while adb occupies the port - and a bar against a
     * guessed maximum would imply a limit that is not real.
     */
    public static int gaugePercent(String key, Readings r, Fps.Sample fps, Layout l) {
        if (key == null || r == null) return -1;
        if (key.startsWith(TEMP)) {
            Long v = r.tempMilliC.get(key.substring(TEMP.length()));
            return tempPercent(v == null ? ABSENT : v);
        }
        switch (key) {
            case "fps":     return Fps.percent(fps);
            // Utilisation when there is one, so this bar and the GPU's below
            // it mean the same thing and can be read against each other. The
            // clock against its ceiling is the fallback for the first tick,
            // which has no delta yet - better than an empty bar for a second.
            case "cpu":     return r.cpuUtilPct != ABSENT
                                   ? percent(r.cpuUtilPct, 100)
                                   : percent(r.cpuMaxKhz(), l == null ? ABSENT : l.cpuMaxKhzCeiling);
            case "gpu":     return percent(r.gpuBusyPct, 100);
            case "fan":     return percent(r.fanPwm, 255);
            case "ram":     return (r.memTotalKb == ABSENT || r.memAvailKb == ABSENT)
                                   ? -1 : percent(r.memTotalKb - r.memAvailKb, r.memTotalKb);
            default:        return -1;      // power has no meaningful ceiling
        }
    }

    // --- when to tick -------------------------------------------------------

    /**
     * Whether ticks should be running.
     *
     * Both conditions, not either. A tab that is not in front has nothing to
     * update, and the panel finishes itself in {@code onStop} rather than
     * lingering, so a timer that outlived the visible window would be reading
     * hardware on behalf of a window that is on its way out.
     */
    public static boolean shouldPoll(boolean telemetryTabSelected, boolean resumed) {
        return telemetryTabSelected && resumed;
    }
}
