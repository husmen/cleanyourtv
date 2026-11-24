# Recommendation artwork companion

Optional artwork fetcher for this branch’s recommendation shelf. It has no app tile, screen, boot receiver, background service, polling, account login, persistent cache or analytics. It is deliberately outside `addons/`: its interface is a signature-protected content provider for this bespoke fork, not the upstream module-card contract.

## Permissions

- `android.permission.INTERNET`: fetch only HTTPS artwork URLs supplied in the launcher’s selected recommendations. Redirects must remain HTTPS; downloads are capped at 2 MiB, with connection/read timeouts and an elapsed-time budget. URLs and media metadata are not logged.
- Defines `com.gathin.tvhome.artwork.READ`, protection level `signature`: only packages signed with the same key can read the provider. The launcher requests that permission, and reads the provider only after checking that the package behind it carries its own signature. No URI grants are supported.

The provider returns bytes over a pipe. Requests use two bounded workers and a six-request queue; saturated requests fail and the launcher retains its placeholder. The launcher reads two images at a time, so its own requests never fill that queue. The launcher passes cancellation through to the provider, which disconnects an active HTTP request and closes its pipe. Timeouts also bound requests if a reading client simply disappears. All fetching starts in response to a client read.

## Build and install for testing

Build with the same persistent Docker signing key as the launcher:

```sh
tools/docker-build.sh companion/artwork lintRelease assembleRelease
TV_HOST=<tv ip> tools/venv/bin/python -I tools/b64push.py companion/artwork/app/build/outputs/apk/release/app-release.apk /data/local/tmp/recommendation-artwork.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/recommendation-artwork.apk'
```

Then install this branch’s launcher with its normal build-and-install script and enable the theme key documented in [FORK.md](../../FORK.md). Installing a second APK is optional: readable local images and all text/navigation work without it. A signature mismatch or inaccessible provider produces placeholders rather than blocking Home.

Disable reversibly with `pm disable-user --user 0 com.gathin.tvhome.artwork`; restore with `pm enable --user 0 com.gathin.tvhome.artwork`. No package uninstall or firmware change is needed.

Device testing has not yet been performed. No screenshot or performance claim is supplied for this provider-only package.
