package com.thorfeatures.screenpanel;

/**
 * Turns a refusing CLI's own output into something worth showing the user.
 *
 * Why this exists: on 2026-11-01 the stick LEDs stopped working and the panel
 * reported it as nothing at all. `stickfx` was in fact refusing, loudly and
 * helpfully:
 *
 *   ! every stick LED colour is "0 0 0", so a charging trigger
 *   ! would drive an invisible LED.
 *   !
 *   ! Either configure colours in Settings -> AYN -> RGB lighting
 *   ! (AynParts owns the colour channel), or pass a colour here:
 *   !     stickfx charge --color 2BE0D8
 *
 * The panel ran the command, saw a non-zero exit, re-read the state, found it
 * unchanged, and redrew the same tile. From the user's side a button that
 * explains itself and a dead button look identical.
 *
 * These CLIs mark their diagnostics with a leading "!", which is what makes
 * the useful part separable from whatever else landed on the stream.
 */
public final class CliMessage {

    /** Longest message worth putting in a toast. */
    static final int MAX = 160;

    private CliMessage() {}

    /**
     * The first coherent sentence of a refusal, or null when the output
     * carries nothing worth showing.
     *
     * Null means "say something generic", not "say nothing": the caller still
     * has to tell the user the command failed. Returning a bare "" or the raw
     * dump would be worse than either.
     */
    public static String summarize(String out) {
        if (out == null) return null;

        StringBuilder sb = new StringBuilder();
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                // A blank line ends the first paragraph. Everything after it
                // is usually the "here is how to fix it" detail, which is too
                // long for a toast and belongs in the CLI's own output.
                if (sb.length() > 0) break;
                continue;
            }
            // Strip the diagnostic marker these scripts use.
            if (line.startsWith("!")) {
                line = line.substring(1).trim();
                if (line.isEmpty()) {            // a bare "!" spacer line
                    if (sb.length() > 0) break;
                    continue;
                }
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(line);
            if (sb.length() >= MAX) break;
        }

        String msg = sb.toString().trim();
        if (msg.isEmpty()) return null;

        if (msg.length() > MAX) {
            // Cut on a word boundary rather than mid-word.
            int cut = msg.lastIndexOf(' ', MAX);
            msg = msg.substring(0, cut > 40 ? cut : MAX).trim() + "…";
        }
        // Sentence-case the leading letter: these messages start lower-case
        // because they read as a continuation of the command line.
        if (Character.isLowerCase(msg.charAt(0))) {
            msg = Character.toUpperCase(msg.charAt(0)) + msg.substring(1);
        }
        return msg;
    }
}
