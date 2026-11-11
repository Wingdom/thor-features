package com.thorfeatures.stickcal;

/**
 * Edits AynParts' {@code joystick_calibration} inside its own preferences
 * file, leaving everything else byte-identical.
 *
 * <h3>Why we write another app's SharedPreferences at all</h3>
 *
 * Because it is what makes a calibration survive a reboot, and because
 * nothing else does. The node itself resets to the kernel default
 * {@code -1350:1350:0:0} on every boot; what restores a real value is
 * AynParts' {@code BootCompletedReceiver} calling
 * {@code CalibrationPersistence.restoreOnBoot}. Writing a value into that
 * preference and cold-booting was verified on 2026-11-10: the node read back
 * byte-identical at 25 s uptime, and since it cannot have come from the
 * kernel default, the restore path demonstrably ran on a hand-written
 * preference.
 *
 * An earlier note in {@code thor/findings/lineageos-aynparts.md} said not to
 * do this, on the grounds that the owning app caches its preferences in
 * memory and would clobber the write. That is true and is why AynParts is
 * force-stopped first. It has no persistent service - one exported activity,
 * two private ones, a boot receiver and two androidx services - so between
 * its boot write and the next time its panel is opened, nothing holds the
 * file.
 *
 * <h3>Why a text edit and not a rewrite</h3>
 *
 * The file also holds the RGB lighting configuration, the gamepad layout, the
 * trigger mode and the digital trigger threshold, several of them with their
 * {@code :node} companions. Regenerating it from what this tool knows would
 * drop every one of those. So the file is read, one element is replaced, and
 * the result is written back.
 */
public final class PrefsXml {

    public static final String KEY = "joystick_calibration";

    /**
     * The path AynParts' preferences actually live at.
     *
     * {@code /data/user_de}, the device-encrypted store, not
     * {@code /data/data} - AynParts is {@code directBootAware}, so its
     * default preferences are in the DE store and the credential-encrypted
     * path does not hold them. Reading the wrong one returns nothing and
     * looks exactly like a never-configured app.
     */
    public static final String DIR =
            "/data/user_de/0/org.lineageos.settings.ayn/shared_prefs";
    public static final String PATH = DIR + "/org.lineageos.settings.ayn_preferences.xml";
    public static final String PACKAGE = "org.lineageos.settings.ayn";

    private static final String HEADER =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n";

    private PrefsXml() {}

    /** The calibration this file currently carries, or null. */
    public static String read(String xml) {
        if (xml == null) return null;
        int open = openTagOf(xml);
        if (open < 0) return null;
        int gt = xml.indexOf('>', open);
        if (gt < 0) return null;
        int close = xml.indexOf("</string>", gt);
        if (close < 0) return null;
        String v = xml.substring(gt + 1, close).trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * The same file with {@code joystick_calibration} set to {@code value}.
     *
     * Handles all three states the file can be in: the key present, the file
     * present without the key, and no file at all. The third is a real case -
     * on this device the directory did not exist until the wizard was first
     * run.
     *
     * @throws IllegalArgumentException if the value is not a calibration.
     *         Checked here rather than at the call site because this is the
     *         last place the value is still structured text; after this it is
     *         a file being handed to a root shell.
     */
    public static String withCalibration(String xml, String value) {
        if (!Calibration.isSafe(value)) {
            throw new IllegalArgumentException("not a calibration: " + value);
        }
        String element = "<string name=\"" + KEY + "\">" + value + "</string>";

        if (xml == null || xml.trim().isEmpty()) {
            return HEADER + "<map>\n    " + element + "\n</map>\n";
        }

        int open = openTagOf(xml);
        if (open >= 0) {
            int close = xml.indexOf("</string>", open);
            if (close >= 0) {
                return xml.substring(0, open) + element
                        + xml.substring(close + "</string>".length());
            }
        }

        int map = xml.lastIndexOf("</map>");
        if (map >= 0) {
            return xml.substring(0, map) + "    " + element + "\n" + xml.substring(map);
        }

        // A file that is neither a map nor empty is not something to patch.
        // Replacing it would discard whatever it is; refusing leaves it alone.
        throw new IllegalArgumentException("no <map> element to insert into");
    }

    /**
     * Index of the opening tag of the calibration element, or -1.
     *
     * Found by locating the attribute and walking back to the {@code <}, so
     * attribute order and whitespace do not matter. The platform's own
     * serializer writes {@code <string name="k">v</string>}, but a file is
     * easier to parse loosely than to depend on that.
     */
    private static int openTagOf(String xml) {
        int at = xml.indexOf("name=\"" + KEY + "\"");
        if (at < 0) at = xml.indexOf("name='" + KEY + "'");
        if (at < 0) return -1;
        int lt = xml.lastIndexOf('<', at);
        if (lt < 0) return -1;
        // Only a <string> element carries a calibration; anything else with
        // this name is not ours to rewrite.
        if (!xml.startsWith("<string", lt)) return -1;
        return lt;
    }
}
