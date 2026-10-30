package com.thorfeatures.screenmove;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: send the foreground app to the other screen.
 *
 * All the decisions live in ScreenStatus and TileRender, which are pure and
 * host-tested. This class is the thin Android shell: it runs the CLI off the
 * main thread, maps the pure result onto a Tile, and shows a dialog when it
 * cannot act.
 *
 * One constraint governs *when* root is asked for. onStartListening() must not
 * touch the CLI: Magisk's prompt cannot take focus over the open Quick
 * Settings shade, so a request made there is rejected rather than prompted and
 * the user never sees a dialog. The shade gets a static subtitle from
 * TileRender.idleLabel() and the first su request comes from onClick(), which
 * is a foreground action the prompt can appear over.
 */
public class ScreenmoveTileService extends TileService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScreenmoveClient client;

    @Override public void onCreate() {
        super.onCreate();
        client = new ScreenmoveClient(ScreenmoveClient.EXEC);
    }

    @Override public void onStartListening() {
        super.onStartListening();
        renderIdle();
    }

    /**
     * Render without asking for root. Deliberately names no ScreenmoveClient
     * call: this runs every time the shade opens, and an su request from here
     * is rejected rather than prompted.
     *
     * There is nothing to read and nothing to cache -- the subtitle names the
     * verb, not a direction, because the direction depends on live display
     * state. So this is synchronous and does no I/O at all.
     */
    private void renderIdle() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(mapState(TileRender.idleState()));
        tile.setLabel(getString(R.string.tile_name));
        tile.setSubtitle(TileRender.idleLabel());
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_screenmove));
        tile.updateTile();
    }

    private void render(boolean available, ScreenStatus st) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(mapState(TileRender.state(available, st)));
        tile.setLabel(getString(R.string.tile_name));
        tile.setSubtitle(TileRender.label(available, st));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_screenmove));
        tile.updateTile();
    }

    private static int mapState(int rendered) {
        switch (rendered) {
            case TileRender.ACTIVE:      return Tile.STATE_ACTIVE;
            case TileRender.UNAVAILABLE: return Tile.STATE_UNAVAILABLE;
            default:                     return Tile.STATE_INACTIVE;
        }
    }

    @Override public void onClick() {
        super.onClick();

        new Thread(new Runnable() {
            @Override public void run() {
                final ScreenStatus before = client.status();

                if (!before.displaysKnown()) {
                    // Either the module is missing / root was denied, or there
                    // is no second display. Both are explained, not silently
                    // ignored -- which is only possible because the tile
                    // renders STATE_INACTIVE rather than STATE_UNAVAILABLE.
                    main.post(new Runnable() {
                        @Override public void run() {
                            // Show the reason on the tile too, not just in the
                            // dialog -- the dialog is dismissed, the tile stays.
                            render(false, before);
                            explain(before);
                        }
                    });
                    return;
                }
                if (!before.anythingMovable()) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            render(true, before);
                            explain(before);
                        }
                    });
                    return;
                }

                main.post(new Runnable() {
                    @Override public void run() {
                        Tile t = getQsTile();
                        if (t != null) {
                            t.setState(mapState(TileRender.busy()));
                            t.setSubtitle(getString(R.string.moving));
                            t.updateTile();
                        }
                    }
                });

                client.toggle();

                // Re-read rather than assuming the move landed: the window
                // manager can decline a move that `am` reports as success.
                final ScreenStatus after = client.status();
                main.post(new Runnable() {
                    @Override public void run() { render(after.displaysKnown(), after); }
                });
            }
        }).start();
    }

    /** Explain why a tap did nothing. Reachable only because the tile stays clickable. */
    private void explain(ScreenStatus st) {
        String msg;
        if (!st.displaysKnown()) {
            msg = getString(R.string.need_root);
        } else {
            msg = getString(R.string.nothing_to_move);
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.tile_name)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        showDialog(d);
    }
}
