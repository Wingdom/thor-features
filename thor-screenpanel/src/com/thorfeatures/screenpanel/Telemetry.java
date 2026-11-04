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

    /**
     * Parse a tick reply.
     *
     * Values are matched against the paths {@code layout} asked for rather
     * than pattern-matched out of the text, so a line this code did not
     * request cannot land in a gauge.
     */
    public static Readings parseTick(String out, Layout layout) {
        Readings r = new Readings();
        Map<String, String> got = new LinkedHashMap<>();
        List<String> meminfo = new ArrayList<>();

        if (out != null) {
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                int i = line.indexOf(':');
                if (i <= 0) continue;
                String path  = line.substring(0, i);
                String value = line.substring(i + 1).trim();
                if (path.equals("/proc/meminfo")) meminfo.add(value);
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
        return r;
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
     * Battery power: microamps and microvolts in, "4.52 W" out.
     *
     * The sign comes from {@code status}, never from the sign of {@code
     * current_now}. Qualcomm builds disagree about which direction is
     * negative, and the node's convention on this device is unverified, so
     * reading direction out of the magnitude would be a guess that renders as
     * a confident number. {@code status} says "Charging" or "Discharging" in
     * words and cannot be misread.
     */
    public static String formatWatts(long uA, long uV, String status) {
        if (uA == ABSENT || uV == ABSENT) return NONE;
        double w = Math.abs(uA / 1e6) * (uV / 1e6);
        String sign = "";
        if (status != null) {
            String s = status.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("charging")) sign = "+";
            else if (s.startsWith("discharging")) sign = "−";  // true minus
        }
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
     * The gauges to show, in display order, for a resolved layout.
     *
     * Driven by what discovery actually found rather than by a fixed list, so
     * a node that is missing or unreadable costs a row instead of showing a
     * zero - an absent reading and a genuine zero must not look alike.
     *
     * Deliberately not every temperature discovery found. This device reports
     * many zones per subsystem and the panel is a glance, not a monitor - a
     * card long enough to scroll would push Mode off the bottom of the
     * screen, which is the one thing the layout is not allowed to do. The
     * three kept are the ones that answer different questions: the SoC's own
     * heat, the board nearest the chassis the user is holding, and
     * {@code usb-therm}.
     *
     * {@code usb-therm} earns its row because it is the open question on this
     * hardware, not because it is hot. It is driven mainly by charge current
     * rather than SoC load, and fanctl's 60 C quiet trip is still reasoned
     * rather than measured because no log has caught it above 42 C. A live
     * reading on the panel during a real charge-plus-game session is exactly
     * the evidence that item has been waiting for.
     */
    public static List<String> gaugeKeys(Layout l, boolean fpsAvailable) {
        List<String> keys = new ArrayList<>();
        if (fpsAvailable) keys.add("fps");
        if (!l.policies.isEmpty()) keys.add("cpu");
        if (l.hasGpu())            keys.add("gpu");
        if (l.hasFan())            keys.add("fan");
        if (l.zones.containsKey("CPU"))  keys.add(TEMP + "CPU");
        if (l.zones.containsKey("Board")) keys.add(TEMP + "Board");
        if (l.zones.containsKey("USB"))  keys.add(TEMP + "USB");
        keys.add("battery");
        keys.add("ram");
        return keys;
    }

    /**
     * How many gauges go in the left column of two.
     *
     * The remainder goes left rather than right so an odd count leaves the
     * shorter column on the trailing edge, which is where the card has slack.
     */
    public static int leftColumnCount(int total) {
        return (total + 1) / 2;
    }

    /** The label for a gauge key. */
    public static String gaugeLabel(String key) {
        if (key == null) return "";
        if (key.startsWith(TEMP)) {
            String g = key.substring(TEMP.length());
            // The group names double as labels, except CPU/GPU which would
            // otherwise collide with the frequency rows above them.
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

    /** The text a gauge shows for one tick's readings. */
    public static String gaugeValue(String key, Readings r, Fps.Sample fps) {
        if (key == null || r == null) return NONE;
        if (key.startsWith(TEMP)) {
            Long v = r.tempMilliC.get(key.substring(TEMP.length()));
            return formatTemp(v == null ? ABSENT : v);
        }
        switch (key) {
            case "fps":     return Fps.format(fps);
            case "cpu":     return formatCpuFreq(r.cpuMaxKhz());
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
            case "cpu":     return percent(r.cpuMaxKhz(), l == null ? ABSENT : l.cpuMaxKhzCeiling);
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
