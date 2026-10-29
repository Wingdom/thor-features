# Screenshots

Referenced from the main `README.md`. Three are wanted:

| File | What it should show |
|---|---|
| `panel.png` | The AYN button panel on the bottom screen — the project's showpiece |
| `tiles.png` | Quick Settings with the fan, screenmove and stickfx tiles visible |
| `appprofiles.png` | The per-game fan profiles app, with a few games listed |

Capture with `adb exec-out screencap -p > docs/img/<name>.png`. Crop the bottom
screen out of a full-frame grab for `panel.png`, since the Thor's two panels
come back as one image.

Until they exist, the main README carries `<!-- SCREENSHOT: ... -->` comments
where each one goes.
