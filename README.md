# EasyDocs Viewer

An offline PDF and Excel viewer for Android, written in Kotlin. It has no ads, no accounts and no tracking, and it has **no internet permission**.

| | |
|---|---|
| Release APK | `release/EasyDocs-v1.0.0.apk` (**392 KB**, signed with APK signature schemes v1 and v2) |
| Minimum Android | **5.0 Lollipop (API 21)** |
| Target Android | 16 (API 36) |
| Package | `com.easydocs.viewer` |
| Permissions | Only `READ_EXTERNAL_STORAGE`, limited to Android 5.0–5.1 (`maxSdkVersion=22`), for `file://` links from old file managers. Newer versions use the system picker and content URIs, which need no permission. |

## Installing on a phone

**Option A: copy the APK to the phone**
1. Copy `release/EasyDocs-v1.0.0.apk` to the phone (USB, Bluetooth, etc.).
2. Open it with the phone's Files app and tap **Install**.
3. If the phone blocks it, allow **Install unknown apps** for the Files app when prompted (Settings → Apps → Special access → Install unknown apps).

**Option B: USB with adb**
1. On the phone, enable **Developer options → USB debugging**.
2. Connect it and run:
   ```bash
   ~/android-toolchain/sdk/platform-tools/adb install -r release/EasyDocs-v1.0.0.apk
   ```

After that, PDF and Excel files offer **EasyDocs** under "Open with" and in the share sheet (WhatsApp, Gmail, Files and other apps).

## Features
- **Home:** PDF and Excel shortcuts, **Browse files** (system file picker, which includes Downloads), recent documents (long-press an entry to remove it), and a Light, Dark or System theme (⋮ → Theme).
- **PDF** (Android's built-in `PdfRenderer`): continuous scrolling, pinch zoom, double-tap zoom, zoom buttons, page counter, and "Go to page" (tap the title or ⋮). Pages render on a background thread into a cache with a fixed memory limit. When zoomed in, only the visible area is re-rendered at full sharpness, so a 1000-page PDF uses the same memory as a 5-page one. Password-protected and damaged files show a clear message.
- **Excel** (bundled SheetJS 0.20.3 in a WebView): XLSX, XLSM and XLS files; multiple sheets as tabs; row and column headers; scrolling in both directions; pinch zoom and zoom buttons; tap a cell to see its full value. It keeps column widths, row heights, merged cells, solid fill colours, number and date formats, and text spilling into empty neighbouring cells. Only the visible cells are drawn, so large sheets scroll smoothly. It is read-only.
- **Android integration:** "Open with" (`ACTION_VIEW`) and Share (`ACTION_SEND`) for `application/pdf`, `.xlsx`, `.xls` and `.xlsm`; content URIs; persistent access to files picked through the system picker.

## Privacy
- The manifest has no `INTERNET` permission, so the app *cannot* reach the network.
- The Excel WebView only loads bundled files from a made-up local address (`https://easydocs.local/`, served by the app itself). Requests to any other address are blocked, and a Content-Security-Policy restricts scripts to these bundled files.
- No analytics, ads, Google Play services or background services. Recent documents are stored only in private app storage.
- When an app shares a PDF in a form that can't be read directly, it is copied into the app's private cache. The copy is deleted when you close the viewer, and leftovers are removed after 6 hours.

## Building
Requirements (already on this machine): JDK 21 (`~/android-toolchain/jdk21`) and the Android SDK (`~/android-toolchain/sdk`, platform 36).
```bash
export JAVA_HOME=~/android-toolchain/jdk21
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug       # unsigned debug build
```
Signing uses `keystore/easydocs-release.jks` and `keystore.properties`; both are git-ignored.
**Back up both files somewhere safe.** Every future update must be signed with the same key, or phones will refuse to install it over the old version.

Test documents can be regenerated with `tools/make-testdocs.sh <dir>` (needs python3 + reportlab, node and LibreOffice).

## Project layout
```
app/src/main/
  AndroidManifest.xml            activities, intent filters, permissions
  java/com/easydocs/viewer/
    Docs.kt                      file-type detection, recent documents, base activity (theme, system-bar insets)
    MainActivity.kt              home screen and system file picker
    PdfActivity.kt               PDF opening (with cache-copy fallback), page navigation
    PdfView.kt                   PDF rendering view: scrolling, zoom, page cache, sharp zoomed tile
    ExcelActivity.kt             WebView setup, local-only request handling, JavaScript bridge
  assets/excel/
    viewer.html/.css/.js         spreadsheet grid (ES5, draws only visible cells)
    polyfills.js                 fills in modern JavaScript functions missing from old WebViews (Android 5)
    xlsx.full.min.js             SheetJS 0.20.3 (Apache-2.0)
  res/                           layouts, vector icons, light and dark colours
tools/make-testdocs.sh           test-file generator
```

## Test results (19 Nov 2026)

Tested on emulators: Android 15 (API 35, Pixel 6 profile) and Android 5.0 (API 21, Nexus 5 profile, with its original Chrome 37 WebView). **Not yet tested on a physical phone** (none was connected); see the checklist below.

| Scenario | Android 15 | Android 5.0 |
|---|---|---|
| Home screen, light theme | ✅ | ✅ |
| Dark theme (system dark mode / in-app switch) | ✅ | ✅ (in-app) |
| PDF from Downloads via the system picker | ✅ | ✅ (via `file://` link, as old file managers send) |
| PDF pinch/double-tap zoom with sharp text | ✅ | ✅ |
| Large PDF (1000 pages): open, go to page 800, fast scrolling | ✅ ~95–126 MB total | ✅ |
| Password-protected PDF → clear message | ✅ | — |
| Damaged PDF → clear message | ✅ | — |
| XLSX with merged cells, fill colours and Unicode | ✅ | ✅ |
| XLS (Excel 97–2003) | ✅ | ✅ |
| Large XLSX: 30 MB, 200k rows × 10 columns (2M cells) | ✅ opens in ~26 s on the emulator, scrolls smoothly | ✅ |
| "Open with" (`ACTION_VIEW`, content URI) | ✅ | ✅ |
| Share from another app (system Files → Share → EasyDocs) | ✅ | — |
| Reopen from Recents after the app is closed | ✅ | — |
| Airplane mode | ✅ | n/a (no network permission) |
| Rotation keeps the current page | — | ✅ |
| Recents entry whose access has expired | ✅ clear message, entry removed | — |
| Ads or network requests | none (no INTERNET permission) | none |

Bugs found and fixed during testing:
- Excel files failed on Android 5's WebView because SheetJS needs JavaScript functions that version lacks → added `polyfills.js`.
- Opening a Recents entry whose access had expired crashed the app → fixed, and the entry is now removed with a message.
- `.xls` failed with the SheetJS "core" build, which lacks codepage tables → switched to the "full" build.
- PDFs with mixed page sizes were scaled to the widest page → each page now fits the screen width.
- WebView renderer crashes (Android 8+) took down the app → now handled with an error message.

### Remaining checklist for a physical phone
- [ ] Install `release/EasyDocs-v1.0.0.apk`
- [ ] Open a PDF and an XLSX from Downloads via **Browse files**
- [ ] Receive a PDF/XLSX in WhatsApp → tap it → choose EasyDocs; also WhatsApp/Gmail → Share → EasyDocs
- [ ] Open a file from the Files app with **Open with**
- [ ] Open a large spreadsheet and a large PDF
- [ ] Repeat with airplane mode on

## Known limitations
- Excel: SheetJS community edition doesn't read fonts (bold, colours), borders or theme-based fill colours; charts, images and formulas aren't shown (formulas show their last calculated values).
- Very large spreadsheets have to be read into memory entirely (XLSX is a zip file); a 30 MB file takes several seconds to parse.
- Password-protected PDFs and spreadsheets aren't supported.
- Recent entries for files shared from other apps (WhatsApp, Gmail) only work while the sending app's permission lasts. If it has expired, the app says so and removes the entry.
