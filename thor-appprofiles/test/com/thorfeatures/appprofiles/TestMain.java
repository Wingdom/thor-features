package com.thorfeatures.appprofiles;

import com.thorfeatures.fanctl.FanMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Host unit tests. No device, no Android, no root. */
public final class TestMain {

    private static int pass = 0, fail = 0;

    static void ok(String label) { pass++; System.out.println("  PASS " + label); }
    static void bad(String label, String why) {
        fail++; System.out.println("  FAIL " + label + "\n       " + why);
    }
    static void eq(String label, Object actual, Object expected) {
        String a = String.valueOf(actual), e = String.valueOf(expected);
        if (a.equals(e)) ok(label);
        else bad(label, "expected [" + e.replace("\t", "\\t").replace("\n", "\\n")
                      + "] got [" + a.replace("\t", "\\t").replace("\n", "\\n") + "]");
    }
    static void isTrue(String label, boolean b) { if (b) ok(label); else bad(label, "expected true"); }
    static void isFalse(String label, boolean b) { if (!b) ok(label); else bad(label, "expected false"); }
    static void throws_(String label, Runnable r) {
        try { r.run(); bad(label, "expected an exception"); }
        catch (IllegalArgumentException e) { ok(label); }
        catch (Exception e) { bad(label, "wrong exception: " + e); }
    }

    public static void main(String[] args) {
        profileTableReading();
        profileTableEditing();
        profileTablePreservation();
        commands();
        ordering();

        System.out.println();
        System.out.printf("appprofiles: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- reading
    static void profileTableReading() {
        System.out.println("ProfileTable - reading");
        eq("empty text yields an empty table", ProfileTable.parse("").size(), 0);
        eq("null text yields an empty table", ProfileTable.parse(null).size(), 0);

        ProfileTable t = ProfileTable.parse(
            "com.a\tfan\tperformance\n" +
            "com.b\tfan\tbalanced\n");
        eq("reads a tier", t.fanTier("com.a"), FanMode.PERFORMANCE);
        eq("reads another tier", t.fanTier("com.b"), FanMode.BALANCED);
        eq("absent package has no override", t.fanTier("com.missing"), null);

        // A junk value reads as "no override", matching what the daemon does,
        // so the UI and the thing doing the work agree.
        ProfileTable junk = ProfileTable.parse("com.a\tfan\twibble\n");
        eq("an unknown tier word is not an override", junk.fanTier("com.a"), null);
        // `default` is not a tier anywhere in this project.
        ProfileTable def = ProfileTable.parse("com.a\tfan\tdefault\n");
        eq("'default' is not an override", def.fanTier("com.a"), null);

        ProfileTable mixed = ProfileTable.parse(
            "com.a\tfan\tquiet\n" +
            "com.a\trgb\tbreathe\n" +
            "com.b\tscreen\tbottom\n" +
            "com.c\tfan\tperformance\n");
        Map<String, FanMode> o = mixed.fanOverrides();
        eq("fanOverrides counts only fan keys", o.size(), 2);
        eq("  in file order, first", new ArrayList<>(o.keySet()).get(0), "com.a");
        eq("  in file order, second", new ArrayList<>(o.keySet()).get(1), "com.c");
        eq("a package with only a non-fan key has no tier", mixed.fanTier("com.b"), null);
    }

    // ---------------------------------------------------------------- editing
    static void profileTableEditing() {
        System.out.println();
        System.out.println("ProfileTable - editing");

        ProfileTable t = ProfileTable.parse("");
        t.setFanTier("com.a", FanMode.PERFORMANCE);
        eq("adding to an empty table", t.render(), "com.a\tfan\tperformance\n");

        t.setFanTier("com.b", FanMode.QUIET);
        eq("a second package appends", t.render(),
           "com.a\tfan\tperformance\ncom.b\tfan\tquiet\n");

        t.setFanTier("com.a", FanMode.BALANCED);
        eq("changing a tier replaces IN PLACE, order kept", t.render(),
           "com.a\tfan\tbalanced\ncom.b\tfan\tquiet\n");

        t.setFanTier("com.a", null);
        eq("a null tier clears the line", t.render(), "com.b\tfan\tquiet\n");

        t.setFanTier("com.b", FanMode.STOCK);
        eq("STOCK clears too - it is not a tier", t.render(), "");

        ProfileTable noop = ProfileTable.parse("com.a\tfan\tquiet\n");
        noop.setFanTier("com.missing", null);
        eq("clearing an absent package changes nothing", noop.render(), "com.a\tfan\tquiet\n");

        ProfileTable ignore = ProfileTable.parse("");
        ignore.setFanTier("", FanMode.QUIET);
        ignore.setFanTier(null, FanMode.QUIET);
        eq("an empty or null package is ignored", ignore.render(), "");

        // The daemon reads the FIRST match, so a duplicate is dead weight that
        // would resurface if the first were cleared.
        ProfileTable dup = ProfileTable.parse(
            "com.a\tfan\tquiet\n" +
            "com.b\tfan\tquiet\n" +
            "com.a\tfan\tperformance\n");
        dup.setFanTier("com.a", FanMode.BALANCED);
        eq("duplicate fan lines are collapsed", dup.render(),
           "com.a\tfan\tbalanced\ncom.b\tfan\tquiet\n");

        ProfileTable dup2 = ProfileTable.parse(
            "com.a\tfan\tquiet\n" +
            "com.a\tfan\tperformance\n");
        dup2.setFanTier("com.a", null);
        eq("clearing removes every duplicate", dup2.render(), "");
    }

    // ----------------------------------------------------------- preservation
    static void profileTablePreservation() {
        System.out.println();
        System.out.println("ProfileTable - preserves what it does not own");
        // THE IMPORTANT ONE. The format is shared with per-app features that
        // are not built yet. An implementation that regenerated the file from
        // its own parsed model would delete every rgb/screen line. These must
        // survive byte for byte.
        String original =
            "# per-app profiles\n" +
            "com.game\tfan\tperformance\n" +
            "com.game\trgb\tbreathe\n" +
            "\n" +
            "com.emu\tscreen\tbottom\n" +
            "com.emu\tfan\tbalanced\n" +
            "this line is malformed\n" +
            "com.other\tunknownkey\tvalue\n";

        ProfileTable t = ProfileTable.parse(original);
        eq("an untouched table renders byte-identically", t.render(), original);

        t.setFanTier("com.game", FanMode.QUIET);
        eq("editing one fan line leaves everything else intact", t.render(),
            "# per-app profiles\n" +
            "com.game\tfan\tquiet\n" +
            "com.game\trgb\tbreathe\n" +
            "\n" +
            "com.emu\tscreen\tbottom\n" +
            "com.emu\tfan\tbalanced\n" +
            "this line is malformed\n" +
            "com.other\tunknownkey\tvalue\n");

        t.setFanTier("com.emu", null);
        eq("clearing a fan line removes only that line", t.render(),
            "# per-app profiles\n" +
            "com.game\tfan\tquiet\n" +
            "com.game\trgb\tbreathe\n" +
            "\n" +
            "com.emu\tscreen\tbottom\n" +
            "this line is malformed\n" +
            "com.other\tunknownkey\tvalue\n");

        // Saving repeatedly must not grow the file.
        ProfileTable grow = ProfileTable.parse("com.a\tfan\tquiet\n");
        String once = grow.render();
        eq("re-parsing a render is stable", ProfileTable.parse(once).render(), once);
        eq("  and again", ProfileTable.parse(ProfileTable.parse(once).render()).render(), once);
    }

    // --------------------------------------------------------------- commands
    static void commands() {
        System.out.println();
        System.out.println("AppwatchCommands");
        String[] argv = AppwatchCommands.suArgv(AppwatchCommands.STATUS);
        eq("su argv length", argv.length, 3);
        eq("  argv[0]", argv[0], "su");
        eq("  argv[1]", argv[1], "-c");
        eq("  argv[2]", argv[2], "/system/bin/appwatch status");

        // No fixed command may carry shell syntax that could chain another.
        String[] fixed = {
            AppwatchCommands.STATUS, AppwatchCommands.ENABLE, AppwatchCommands.DISABLE,
            AppwatchCommands.FANCTL_GET
        };
        boolean clean = true;
        for (String c : fixed) {
            if (c.matches(".*[;|&`$()<>\\\\\"'].*")) { clean = false; break; }
        }
        isTrue("fixed commands carry no shell metacharacters", clean);
        // READ_TABLE is the one fixed command carrying shell syntax, and the
        // reason is specific: a missing table must read as EMPTY, not as a
        // failure. A plain `cat` exits 1 on a missing file, and the module
        // creates its state dir without a profiles file, so a fresh install
        // read as "could not read" -- which makes the app refuse to save.
        // Observed on device before this was fixed.
        eq("a missing table reads as empty, not as a failure",
           AppwatchCommands.READ_TABLE,
           "if [ -e /data/adb/thor-appwatch/profiles ]; then"
         + " cat /data/adb/thor-appwatch/profiles; fi");

        eq("writeTable uses the staged path and the table",
           AppwatchCommands.writeTable("/data/data/com.thorfeatures.appprofiles/files/staged"),
           "mkdir -p /data/adb/thor-appwatch"
         + " && cat /data/data/com.thorfeatures.appprofiles/files/staged"
         + " > /data/adb/thor-appwatch/profiles"
         + " && chmod 600 /data/adb/thor-appwatch/profiles");

        // The staged path is the only non-literal that reaches a command, so
        // it is the only thing that needs validating.
        throws_("rejects a semicolon",  () -> AppwatchCommands.writeTable("/tmp/a;rm -rf /"));
        throws_("rejects a backtick",   () -> AppwatchCommands.writeTable("/tmp/`id`"));
        throws_("rejects a space",     () -> AppwatchCommands.writeTable("/tmp/a b"));
        throws_("rejects $(...)",       () -> AppwatchCommands.writeTable("/tmp/$(id)"));
        throws_("rejects a quote",     () -> AppwatchCommands.writeTable("/tmp/a\"b"));
        throws_("rejects path traversal", () -> AppwatchCommands.writeTable("/tmp/../etc/passwd"));
        throws_("rejects a relative path", () -> AppwatchCommands.writeTable("files/staged"));
        throws_("rejects empty",       () -> AppwatchCommands.writeTable(""));
        throws_("rejects null",        () -> AppwatchCommands.writeTable(null));

        String status =
            "daemon:    running (3641)\n" +
            "processes: 3641 4187\n" +
            "enabled:   yes\n" +
            "fanctl:    quiet\n" +
            "baseline:  \n" +
            "applied:   \n" +
            "overrides: 1\n";
        isTrue("parses enabled: yes", AppwatchCommands.parseEnabled(status));
        isTrue("parses daemon running", AppwatchCommands.parseDaemonRunning(status));
        eq("an empty baseline reads as null", AppwatchCommands.parseBaseline(status), null);

        String off = status.replace("enabled:   yes", "enabled:   no")
                           .replace("daemon:    running (3641)", "daemon:    not running")
                           .replace("baseline:  \n", "baseline:  quiet\n");
        isFalse("parses enabled: no", AppwatchCommands.parseEnabled(off));
        isFalse("parses daemon not running", AppwatchCommands.parseDaemonRunning(off));
        eq("parses a held baseline", AppwatchCommands.parseBaseline(off), "quiet");

        // Missing output must not read as "switched off": the module enables
        // itself at install, so absent means "cannot tell", and showing off
        // would invite a toggle that changes nothing.
        isTrue("absent status defaults to enabled", AppwatchCommands.parseEnabled(""));
        isTrue("null status defaults to enabled", AppwatchCommands.parseEnabled(null));
        isFalse("absent status is not 'running'", AppwatchCommands.parseDaemonRunning(""));
    }

    // --------------------------------------------------------------- ordering
    static void ordering() {
        System.out.println();
        System.out.println("AppEntry - list order");
        List<AppEntry> l = new ArrayList<>();
        l.add(new AppEntry("com.zeta.app",  "Zeta",   false, null));
        l.add(new AppEntry("com.alpha.app", "Alpha",  false, null));
        l.add(new AppEntry("com.game.two",  "Game B", true,  null));
        l.add(new AppEntry("com.game.one",  "Game A", true,  null));
        l.add(new AppEntry("com.conf.app",  "Zebra",  false, FanMode.PERFORMANCE));
        AppEntry.sort(l);
        eq("configured app first, whatever its name", l.get(0).pkg, "com.conf.app");
        eq("then games, alphabetically", l.get(1).label, "Game A");
        eq("  second game", l.get(2).label, "Game B");
        eq("then the rest, alphabetically", l.get(3).label, "Alpha");
        eq("  last", l.get(4).label, "Zeta");

        // A configured game still sorts above an unconfigured one.
        List<AppEntry> g = new ArrayList<>();
        g.add(new AppEntry("com.g.plain", "Plain Game", true, null));
        g.add(new AppEntry("com.g.conf",  "Zzz Game",   true, FanMode.QUIET));
        AppEntry.sort(g);
        eq("configured beats game-ness", g.get(0).pkg, "com.g.conf");

        // Ties must be total and stable: two apps sharing a label must not
        // swap places between openings.
        List<AppEntry> same = new ArrayList<>();
        same.add(new AppEntry("com.b.dup", "Same", false, null));
        same.add(new AppEntry("com.a.dup", "Same", false, null));
        AppEntry.sort(same);
        eq("equal labels break on package", same.get(0).pkg, "com.a.dup");
        List<AppEntry> again = new ArrayList<>(same);
        AppEntry.sort(again);
        eq("  and the order is stable", again.get(0).pkg, "com.a.dup");

        // A STOCK tier is not an override, so it must not float to the top.
        List<AppEntry> stock = new ArrayList<>();
        stock.add(new AppEntry("com.stock", "Zzz", false, FanMode.STOCK));
        stock.add(new AppEntry("com.plain", "Aaa", false, null));
        AppEntry.sort(stock);
        eq("a STOCK tier does not count as configured", stock.get(0).pkg, "com.plain");
        isFalse("  and hasOverride() is false", stock.get(1).hasOverride());
    }
}
