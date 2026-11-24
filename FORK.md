# Category recommendations on the minimal launcher

This branch adds an optional shelf for the focused dock app using Android TV’s public Watch Next and preview-channel records. It keeps the existing dock, input controls and Home handover. It is a bespoke fork, not an upstream feature proposal. No install or device change is part of preparing this branch.

## Interaction

- The dock is one sideways row at the top of the screen, under the header. Left and Right walk it; with `columns` 6 it shows six slots at a time. Up reaches the top controls; Down from them returns to the dock tile it left.
- Under the dock: the focused app’s name, then one row per category, each a strip of up to ten cards that scrolls sideways. A card is its artwork, at the aspect ratio the app published, with the title, episode details and playback progress under it. Further rows scroll up into view.
- Down from the dock enters the first row. Left and Right move along a row and stop at its ends. Up and Down move between rows, each row keeping the card it was left on; Up from the first row, or Back from anywhere in the shelf, returns to the dock tile. Down on the last row stays put.
- Selecting a card opens the publishing app’s link; an unavailable link falls back to that app.
- Hold OK on an app’s tile and pick Recommendations to choose its categories (the tile menu, `tile_menu`, on by default). An app that has published nothing yet offers the four Watch Next types, so its rows can be chosen ahead of time. The choice persists across reboot. Continue Watching and Next Episode are selected initially. New Arrivals and Watchlist are opt-in. Preview channels follow their initial browsable flag until an explicit choice is saved. Channels with no browsable flag are never all silently enabled; select the desired channels in the chooser.
- Where rows would be, a single line can stand instead: “Allow shared TV recommendations” (no permission yet) or a retry line (the provider could not be read). Each takes the focus and acts on OK. An app with no row to show, because it publishes nothing or nothing in a selected category, shows only its name.
- The dock’s band covers the six dock apps. Its right end is open and fades out under the first app after the dock, and its edge takes the hue of the glow behind it, following the glow’s own transition; a grey glow leaves the edge plain. Both come with the shelf and go with it (`dock_band_all` keeps the closed band behind every app).
- Updates do not replace cards while the remote is focused on them. Moving back to the dock applies pending results.
- The feature is off by default and needs `single_row`. With less than one row’s height free under the dock (very large tiles) the shelf stays hidden. The app name above the dock (`dock_title`) is not shown while the shelf is on: the shelf names the app. The initial test UI labels are English; app titles and channel names retain their supplied language.

## Enable for testing

Add `"recommendation_shelf": true`, `"single_row": true` and `"columns": 6` to the existing theme’s `config.json`, preserving all its other settings (`themes/dock-glass` already has the last two). Apply it with `tools/theme.sh` after installing this branch. Turning the key off restores the original layout, with no recommendation queries, observer or image workers created.

On first use, select “Allow shared TV recommendations” and approve TV listings access. Alternatively, explicitly grant it using the existing TV client:

```sh
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS'
```

Each app must actually publish readable rows. The shelf does not reproduce Google TV’s proprietary feed, scrape YouTube, connect directly to Jellyfin, or require media-account credentials. Empty categories may remain empty until an app has been opened and has published items. Use the category chooser to enable YouTube’s available preview channels rather than assuming they are personalized recommendations.

## Artwork and permissions

The launcher adds `android.permission.READ_TV_LISTINGS` and the signature permission `com.gathin.tvhome.artwork.READ`. It has no `INTERNET` permission and no new library dependencies. Local `content://` and `android.resource://` artwork can load directly. A local image provider may itself fetch artwork from a server.

HTTPS posters use the optional [artwork companion](companion/artwork/README.md), a separate provider-only package. Without it, cards retain a play-icon placeholder and full title/source metadata. The launcher reads the companion’s provider only when the package behind it carries the launcher’s own signature, so no other app can stand in for it. HTTP and file artwork are not loaded. The companion’s signature permission requires both packages to be built with the same signing key. Installing the companion before the launcher is recommended.

## Build

Use the existing Docker build tool, which preserves the signing key in its Gradle volume:

```sh
tools/docker-build.sh launcher testReleaseUnitTest lintRelease assembleRelease
tools/docker-build.sh companion/artwork lintRelease assembleRelease
```

The APKs remain ignored build outputs. No binaries belong in commits. This feature is larger than an upstream one-kilobyte switch; record its actual APK size and any upstream size-gate failure rather than changing that gate or CI.

The new key is documented in the theme Keys table. The upstream screenshot-contract test cannot pass for it until a real-TV screenshot is captured after installation; no fixture or unrelated screenshot stands in for device evidence. The permission-contract test lists this fork’s two additional read permissions explicitly and still rejects launcher network permissions and connection code.

Use the existing `tools/b64push.py` and `tools/tv.py` to install the companion as described in its README. Install the launcher with the existing `SKIP_BUILD=1 TV_HOST=<tv ip> launcher/build-and-install.sh`; keep its normal safety checks. No accessibility helper is needed for this feature.

## Checks before accepting the TV test

1. Build/unit tests/lint pass for both packages; run the repository Python suite.
2. With the key off, confirm the original dock, title and navigation.
3. With the key on, exercise denied permission, empty data, a failing provider and retry.
4. From the YouTube and Moonfin tiles’ hold-OK menus, choose categories; verify one row per category, the ten-card limit, poster and 16:9 artwork widths, episode identity and progress.
5. Exercise rows of uneven length and more rows than fit on screen. Test every D-pad edge (dock ends, row ends, first and last row) and return from the top controls, app playback and the category dialog.
6. Switch dock apps while artwork is slow/offline; old cards must not appear under a different app. Verify that disabling the companion leaves functional text cards.
7. Reboot and check Home, category selections, physical remote buttons and casting. Performance measurement is intentionally deferred at the owner’s request.

## Revert

First turn `recommendation_shelf` off in the theme. The previous upstream APK can be reinstalled using the existing build/install tool and the same signing key; the original app identity and Home component are unchanged. The companion can be left installed or disabled with `pm disable-user --user 0 com.gathin.tvhome.artwork`; it has no boot receiver or background service. Re-enable it with `pm enable --user 0 com.gathin.tvhome.artwork`. The debloat lists and their recovery records are unchanged.
