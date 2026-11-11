package com.thorfeatures.stickcal;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What this app keeps on disk: the way back.
 *
 * Nothing here is a setting. The whole file exists so that every write this
 * app makes has a documented way back, recorded before the first write and
 * never overwritten afterwards - because the second time the tool is run, the
 * "current" value is this tool's own output, and saving that over the real
 * original would quietly destroy it.
 *
 * The same values are already saved outside the repository at
 * {@code thor/findings/originals/}, by hand, on 2026-11-10. This is the
 * on-device copy, so a restore does not need a host.
 */
public final class Store {

    private static final String TAG = "stickcal";

    private static final String ORIGINAL_CAL = "original-calibration.txt";
    private static final String ORIGINAL_PREFS = "original-prefs.xml";
    private static final String APPLIED = "applied.txt";
    static final String STAGED_PREFS = "staged-prefs.xml";

    private final File dir;
    private final File cache;

    public Store(Context c) {
        dir = c.getFilesDir();
        cache = c.getCacheDir();
    }

    /**
     * Record an original, once and only once.
     *
     * @return true if this call is what created it
     */
    public boolean saveOriginalCalibration(String value) {
        return writeOnce(new File(dir, ORIGINAL_CAL),
                "# /sys/class/moorechip-joystick/joystick/calibration\n"
              + "# as found by thor-stickcal on " + today() + ", before any write\n"
              + value + "\n");
    }

    public boolean saveOriginalPrefs(String xml) {
        return writeOnce(new File(dir, ORIGINAL_PREFS), xml);
    }

    /** The recorded original calibration string, or null if none was saved. */
    public String originalCalibration() {
        String s = read(new File(dir, ORIGINAL_CAL));
        if (s == null) return null;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#") && Calibration.isSafe(t)) return t;
        }
        return null;
    }

    public String originalPrefs() { return read(new File(dir, ORIGINAL_PREFS)); }

    public boolean hasOriginal() { return originalCalibration() != null; }

    /** The last value this app applied, for the UI to show on a later run. */
    public void saveApplied(String value) {
        write(new File(dir, APPLIED), value + "\n" + today() + "\n");
    }

    public String appliedValue() {
        String s = read(new File(dir, APPLIED));
        if (s == null) return null;
        String first = s.split("\n")[0].trim();
        return Calibration.isSafe(first) ? first : null;
    }

    /**
     * Stage a preferences file for root to install.
     *
     * Written here and handed over as a path rather than as a shell argument.
     * A whole XML document passed through two layers of quoting is a quoting
     * bug waiting to happen, and this file also carries AynParts' RGB and
     * trigger configuration - a mangled write would cost the user those too.
     */
    public File stagePrefs(String xml) throws IOException {
        File f = new File(cache, STAGED_PREFS);
        write(f, xml);
        if (!f.isFile() || f.length() == 0) throw new IOException("staging produced no file");
        return f;
    }

    public void clearStaged() {
        File f = new File(cache, STAGED_PREFS);
        if (f.exists() && !f.delete()) Log.w(TAG, "could not remove " + f);
    }

    /** Where the originals are, for the UI to tell the user. */
    public String originalsPath() { return dir.getAbsolutePath(); }

    // ------------------------------------------------------------- plumbing ---

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    private boolean writeOnce(File f, String content) {
        if (f.isFile() && f.length() > 0) return false;
        write(f, content);
        return true;
    }

    private void write(File f, String content) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(content.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "could not write " + f + ": " + e);
        }
    }

    private String read(File f) {
        if (!f.isFile()) return null;
        try (InputStream i = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int k = i.read(b, n, b.length - n);
                if (k < 0) break;
                n += k;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "could not read " + f + ": " + e);
            return null;
        }
    }
}
