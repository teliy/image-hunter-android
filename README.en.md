# Image Hunter — Android

Mobile analog of [Image Hunter for Windows/Linux](https://github.com/teliy/image-hunter):
web image search, orange checkboxes on result tiles, and bulk downloading of the
checked images to the phone gallery.

Same core as the desktop app: an embedded browser (WebView) opens the search
results page, and the **exact same JavaScript** checkbox injector from the
desktop version (`app/core/extractor.py`) is injected into it — tile heuristics
for Yandex, DuckDuckGo, Google, Bing, Brave and Sogou, proxy-thumbnail
unwrapping and host-lifting to the wrapper that knows the original URL.

## Features (v0.3.0)

- **Search** — 11 engines with the same SERP URLs as desktop: Yandex,
  DuckDuckGo (safe), Google, Bing, Brave, Startpage, Mojeek, Baidu, Sogou,
  Naver, Mail.ru;
- **Tile checkboxes** — mark images right on the results page; the download
  button shows the checked count;
- **Download checked** — fetches the big original for every tile (`best`,
  then fallback `alts`), saves to `Pictures/ImageHunter` with the SERP page
  as `Referer`, image validation and concurrency 3;
- **Collect links** — download every image link found on the page
  (best candidates first) after confirmation;
- **Desktop version of pages** — User-Agent toggle in Settings: some engines
  don't allow checkboxes on their mobile SERP;
- **Downloads** — grid of downloaded images, preview, Share, Delete
  (no storage permission needed — MediaStore);
- **Log** — the same events as desktop: how many tiles were recognized,
  what was downloaded and what failed;
- **Languages** — Russian, English, Chinese (Settings → Language);
- **In-app updates** — Settings → “Check for updates” (plus a silent check
  once a day): the APK is fetched from GitHub Releases and handed to the
  system installer, just like on desktop;
- **WebDAV** — upload saved images to your own server: “Upload all to WebDAV”
  in the Downloads tab (re-running sends only new ones; already-uploaded are
  skipped), single upload in the image preview, and auto-upload of new
  downloads (Settings → WebDAV: server URL, login/password, folder,
  connection test). Desktop lessons applied: background requests with
  timeouts, sequential uploads — the UI never freezes;
- the system Back button acts as "back in browser".

## Install

### Ready-made APK

1. Open [Actions](https://github.com/teliy/image-hunter-android/actions) → the
   latest successful **build** run → artifact `ImageHunter-…-debug-apk`;
2. Download it, allow installing from unknown sources, install;
3. Or check [Releases](https://github.com/teliy/image-hunter-android/releases) —
   every tag ships an APK.

Requires Android 10 (API 29) or newer. The app only requests INTERNET (and,
for in-app updates, the "install unknown apps" right — Android will ask for
it the first time you update).

### Updating to a new version

Starting from v0.2.0 you can update right in the app:
**Settings → Check for updates**. The app checks
[Releases](https://github.com/teliy/image-hunter-android/releases), downloads
the APK and launches the installer; on the first update Android will ask you
to allow Image Hunter to "install unknown apps". All project APKs are signed
with the same key, so the new version installs over the old one without
uninstalling. The automatic check runs at most once a day and only notifies
about a new version.

### Build from source

```bash
git clone https://github.com/teliy/image-hunter-android
cd image-hunter-android
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/
```

Requires JDK 17 and the Android SDK (just open the project in Android Studio).

## Differences from the desktop version

See the table in [README.md](README.md) (Russian) — Android v0.3.0 covers the
search + checkboxes + bulk download core; "Find everywhere", format conversion
and the folder tree are on the roadmap. Android WebView cannot execute JS in
cross-origin frames, so the Mail.ru results inside a nested Yandex frame get
no checkboxes.

## Privacy

No analytics, no server side: requests go only to search engines, image
host sites and your own WebDAV server. WebDAV settings (including the
password) are stored only on the device. The log is kept locally in app
memory.

## License

MIT — same as the desktop version.
