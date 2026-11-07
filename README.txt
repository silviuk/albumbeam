PhotosBeam — personal photo and video viewer for Android TV

Version 0.2.3: formerly AlbumBeam. Both app labels, notifications, diagnostics,
launcher icons and the TV banner now use PhotosBeam. Install these APKs over
the previous apps; do not uninstall. App package IDs, signing certificate, private
paired-device storage and discovery protocol remain unchanged. The GitHub URL
remains https://github.com/silviuk/albumbeam. No new Google Cloud project or OAuth
client is needed. Optionally change the app name under Google Auth Platform >
Branding in your existing project to match the new consent-screen name.

The launcher has separate vector foreground/background layers and an Android 13+
monochrome layer for themed icons. The TV banner includes the PhotosBeam wordmark.


Development version 0.2.0
The phone now has Photos, Remote and Settings destinations, dynamic system colors,
light/dark themes, edge-to-edge insets and adaptive navigation. The TV uses
focusable media cards, a simpler home screen and a separate settings/log panel.

Pair once after installing this version on BOTH devices. Device identity and
connection credentials are encrypted with an Android Keystore AES key and saved
privately with backups disabled. Pairing survives launches and reboots; open
PhotosBeam on both devices to reconnect. Discovery finds the same pinned TV after
its IP or listening port changes. End viewing retains pairing; Forget pairing
revokes the saved device. Google tokens, picker sessions and media selections
remain temporary and can expire independently. A reinstall/data reset loses
pairing. Automatic rediscovery requires a LAN that permits DNS-SD/mDNS.

Three phone selection entry points are available:
- Google Photos: existing Picker API flow; the TV fetches directly from Google.
- Phone & cloud picker: Android's native multi-select picker, including eligible
  cloud providers enabled on the phone. No broad media-library permission.
- Choose files on phone / Gallery > Share > PhotosBeam: select local originals or
  downloaded files using Documents or your gallery's selection tools. Confirm
  Send shared items to TV after the companion opens. Gallery sharing limits vary;
  a web link shared from Google Photos does not grant access to its media files.

Native/file selections are streamed from the phone using authenticated HTTP range
requests, without PhotosBeam transcoding, whole-file buffering or disk copies.
Android picker transcoding is explicitly disabled and original media format is
requested when opening provider files on supported Android versions. Cloud
providers still control which file they supply; original uploads are not promised.
Keep the phone on the same network. A foreground sharing notification has a Stop
button; Stop phone sharing also revokes access. Sharing stops after service/process
termination or reboot and files must be selected again. Ending/replacing a session
revokes access on a best-effort basis, including a stop request to the phone.

Settings > Encrypt media with HTTPS may be switched off before the next native
selection. This sends phone-to-TV media over plain HTTP on numeric private-LAN
addresses. Other devices on that network may observe media and its temporary
access grant. Pairing/control remain encrypted; saved credentials remain encrypted.
Google API and media connections always retain HTTPS. Turning this off does not
change Google processing, source quality or buffering on Google's servers.

Video playback chooses the highest supported resolution, then frame rate and
bitrate among available tracks. The TV requests a 4K/60Hz display mode when
available; the OS may retain another mode. Quality & info shows the delivered
video track, reported fps/bitrate, decoder output size, actual display timing and
HTTP/HTTPS transport. Unknown frame rate is reported explicitly. A compatible
4K60 file, decoder, display mode and sufficient LAN throughput are all needed.
Google Photos Picker's =dv video download is transcoded by Google; there is no
API parameter for an original 4K60 stream. Local original files are the suitable
route when unchanged source video matters. PhotosBeam never transcodes videos.

Select all / album / day / week
Google's picker owns its selection controls. PhotosBeam cannot add Select all or
retrieve an entire existing album, timeline, day or week through the Picker API.
Search album titles/dates and use the controls Google presents; desktop range
selection behavior is not guaranteed. Google's session limit is 2,000 items.
Android's picker also owns its controls and its device-dependent selection limit.
For local media, use your gallery's Select all or date groups when offered, then
Share > PhotosBeam. Alternatively download originals into a local folder and use
Choose files on phone. PhotosBeam accepts at most 2,000 files per session.

Development verification
See VALIDATION-0.2.0.txt for checks completed and checks blocked in this environment.
Do not treat the source version as a published, device-validated release.


0.2.1 playback and remote update
Google video authorization and the download origin now use separate connection
pools. The signed URL stays in memory; OAuth is removed on cross-origin redirects.
This addresses a possible HTTP/2 connection-reuse problem seen in device logs.
If the download host still stalls, hold OK, open Quality & info and try the
HTTP/1.1 connection option, or select the file from the phone.
Fullscreen controls: Up/Down = previous/next; video Left/Right = seek 10 seconds;
OK = play/pause; hold OK = actions. Back closes actions, then returns to the grid.
On photos, Left/Right also changes items and OK opens actions. Controls hide after
three seconds. Menus retain normal directional focus navigation.
The app queries actual decoder capabilities and prefers supported hardware
formats. When multiple tracks exist, it then chooses resolution/fps and codec
preference. A single Google-supplied video cannot be changed into another codec
without an alternate source or transcoding. The app performs no transcoding.
See VALIDATION-0.2.1.txt for verification and outstanding physical-device checks.


0.2.2 viewing controls
Hold OK > Zoom / pan. OK cycles zoom through 1x, 1.5x, 2x, 3x and 4x; use the
D-pad to move around the image and Back to return to fit. Hold OK again for
incremental Zoom +/- and Fit actions. Keyboard +/- and 0 also work. Normal
Up/Down item navigation and video Left/Right seeking resume outside zoom mode.
Phone Remote now includes a pinch/drag touchpad, zoom/fit and directional buttons.
Open media on the TV first. The touchpad is relative, not an image preview. It
coalesces input to eight requests/second maximum with no queued pointer backlog.
Zoom resets for each new item and magnifies the existing up-to-4K photo decode;
it does not request a higher-resolution image or increase the bitmap budget.
Video uses presentation transforms on the hardware SurfaceView path; no extra
CPU video filtering or re-encoding. Device-specific 4K/HDR performance needs testing.
TV viewing overlays now use a pure-black foundation, smaller translucent status
pill and contextual controls that disappear after three seconds. TV native focus
components retain legibility and target size, following the current TV UI guides.
Photo warmth in TV settings/More cycles Off/Mild/Warm/Strong and persists across
launches. It reduces green/blue without lifting black; video colors stay unchanged.
See VALIDATION-0.2.2.txt for checks and outstanding physical-device validation.

Build prerequisites
Install Android Studio, JDK 17, Android SDK Platform 37.0 and build tools. Set
local.properties sdk.dir to your SDK directory. Run ./gradlew assembleDebug test.
The project has phone and tv product flavors with separate application IDs.
Target SDK is deliberately 36; compile SDK 37 supports current AndroidX libraries.
On a future target SDK 37 upgrade, implement ACCESS_LOCAL_NETWORK permission UI
on both devices before raising the target.

Google Cloud setup (required before Google authorization can work)
1. Create a Google Cloud project and enable Google Photos Picker API.
2. Configure Google Auth Platform branding, audience External / Testing, and
   add your own Google account as a test user.
3. Add only https://www.googleapis.com/auth/photospicker.mediaitems.readonly.
4. Create an Android OAuth client for dev.personal.photostv.phone and the SHA-1
   of the certificate used to sign your installed phone APK. Obtain the SHA-1
   using ./gradlew signingReport or keytool -list -v -keystore YOUR_KEYSTORE.
   An OAuth client secret is neither needed nor bundled in these apps.
5. Use the same Google account in authorization and the Photos picker browser.
   Google consent warnings/testing restrictions can apply to this private app.

Installation and use
Install albumbeam-phone-v0.1.apk on an Android phone with Google Play services and
albumbeam-tv-v0.1.apk on Android/Google TV (Android 8+). Using Android platform-tools:
  adb -s PHONE_SERIAL install -r albumbeam-phone-v0.1.apk
  adb -s TV_SERIAL install -r albumbeam-tv-v0.1.apk
Both apps are named PhotosBeam. Launch the TV app, then open the companion and
choose Scan TV QR. On first use, Google's barcode-scanner component may download.
Choose Select photos on the phone, authorize Google access, and search for your
album title in Google Photos. Select items and finish. Watch the TV populate.
The picker supports up to 2,000 items; it does not grant ongoing album access.
Use your TV remote to open an item, navigate, play/pause, seek, or start slideshow.
The next/previous controls pause slideshow advancement; resume using Slideshow.
Use Reconnect authorization on the phone if TV requests it, or select again if
the selection has expired. Replacing a selection ends the previous session.

Troubleshooting
Pair again if the QR is over five minutes old or an interrupted pairing lost its
acknowledgement. Pairing QR codes are single-use. Certificate mismatch is fatal;
never disable certificate validation. Use TV Settings > Forget paired phone to revoke credentials and generate a new QR.
Guest Wi-Fi, client isolation, VPNs and changing network addresses can interrupt
pairing. Use the same ordinary LAN; saved pairing reconnects through discovery after network changes.
403/auth errors: verify API enablement, OAuth package, signing SHA-1, scope and
test-user account. Retry after connectivity returns. Use Skip for failed media.
Process death loses the viewing session. Reopen both apps to reconnect, then select again.

Privacy and limitations
Tokens and session state are in memory; no photo collection is saved. Image disk
cache and Android app backup are disabled. Ending a session clears local state;
Google session deletion is best effort when offline. Android can retain consent
in Google Play services; disconnect the app in Google Account settings to revoke.
Photos are fitted to the display. Google video downloads are transcoded. Original
file/video resolution and HDR preservation are not guaranteed. A 4K download does
not prove a 4K render surface; physical device validation is still required.

Signing
Delivered debug APKs are for personal testing. Preserve a private signing key
outside this source tree for future updates and register its SHA-1 in Cloud.
Losing the signing key requires reinstalling and updating the OAuth registration.
Do not commit keys, access tokens, local.properties or Google credentials.

Validation
Automated PairGate tests cover wrong secrets, replay, concurrent attempts, fingerprints and five-minute expiry.
Compilation/unit tests do not establish Google account integration or TV quality.
Manual acceptance: configured-account authorization; cancellation/denial;
multiple result pages; mixed photos/videos; remote focus and seeking; portrait
orientation; memory under a large selection; unsupported codecs; token renewal;
expired session; network loss; 4K surface/output verification on physical TV.

Release APK signing
The v0.1 APKs use a personal-test debug certificate. Its public fingerprints are
in CERTIFICATE.txt; use SHA1 for your Android OAuth client registration. Signing
keys are not included in the repository or release. Locally built APKs use your
own debug certificate; register its SHA1 instead.

Release status
v0.1 is an initial testing release. Builds, unit tests and Android lint pass.
Google account integration, physical TV playback and 4K rendering are unverified.
PhotosBeam is an independent project and is not affiliated with Google.

Pairing fix in v0.1.1
Upgrade both apps to v0.1.1, then choose End / Pair again on the TV and scan the
new QR using the phone companion. The TV replaces its old local TLS certificate
key to permit prehashed TLS signing and explicitly negotiates TLS 1.2 for older
TV compatibility. Certificate pinning remains required. The APK signing key and
OAuth package name are unchanged; Google Cloud configuration remains valid.

The Android instrumentation test PairingTlsTest exercises legacy key migration,
real pinned TLS pairing, certificate mismatch rejection, session transfer and
replay rejection. Run connectedPhoneDebugAndroidTest on a connected Android
device; it cannot be run without a device/emulator.

Diagnostics in v0.1.2
Both apps show their installed version and use a TV/photo/beam launcher icon.
Choose Show activity log to inspect timestamped pairing, TLS, Google API status,
selection loading and video events. Copy log copies the bounded 120-event buffer
to the device clipboard. Logs are memory-only; operational events also appear in
Android logcat under PhotosBeam. They exclude Google tokens, QR secrets, media
URLs, selected filenames and session IDs. TLS exceptions include their nested
causes to help diagnose the S24 Ultra / Philips TV handshake failure, which is
not yet confirmed fixed. Send the phone and TV logs after reproducing the issue.

TV self-test in v0.1.3
The TV now binds its exact private-key handle and certificate chain to TLS
explicitly, avoiding key-manager provider enumeration that can silently skip
Android Keystore identities. At startup and after End / Pair again it makes a
certificate-pinned HTTPS request to its own loopback interface. This does not
consume the QR secret and exposes no photos or credentials. The activity log
records local TLS self-test PASSED or FAILED. Phone-to-TV connectivity still
needs checking on hardware; a passing loopback test checks only the local stack.
Server socket failures are also logged even when they are not SSLException.

Memory-only identity in v0.1.4
Pairing no longer uses Android Keystore. Each pairing server creates a fresh
software EC identity in memory using OkHttp HeldCertificate. Its fingerprint
is authenticated through the QR; pin checks and single-use secrets remain
mandatory. Private keys and certificates are not saved to storage. The TV still
runs its loopback self-test. The v0.1.2 phone app remains protocol-compatible.
WirePairingTest runs actual loopback TLS/HTTP on the JVM, covering successful
pairing and delivery, rejected pins, unauthorized transfers, replay protection,
and identity rotation. Android instrumentation remains a separate device check.

Pairing options in v0.1.5
Update both apps for these options. On TV choose Full-screen QR to maximize the
code with a white background; Back or Close QR returns to the normal screen.
For manual pairing choose Show 6-character code. On phone choose Enter
6-character TV code, select the nearby TV, compare all four verification groups
with the TV display, then enter the code and Connect. The verification comparison
authenticates the discovery-provided certificate; never confirm a mismatch.
The random code expires in five minutes, permits five guesses, and is single-use
across both pairing paths. End / Pair again generates a new code. Discovery uses
local DNS-SD/mDNS with no server; guest networks or multicast filtering may block
it, in which case use QR pairing. Codes and pairing secrets are never logged.

On phone Scan TV QR (auto zoom) enables Google's automatic zoom. Scan with zoom
controls opens a local CameraX scanner with 1x/2x/4x presets and a zoom slider
within device limits (up to 10x). It requests camera permission on the phone only.
If denied, use the Google scanner or manual code. Camera images are decoded
locally and never saved or uploaded. Camera behavior and remote full-screen
focus still need physical-device validation. Google Cloud settings are unchanged.

Media viewer and log transfer in v0.1.6
Photos and videos now occupy a dedicated full-screen viewer, with compact
controls overlaid instead of sharing a column with logs. Full-size photos use
explicit screen-sized decoding and a fixed-size SurfaceView buffer up to 4K,
independent of thumbnail sampling. Logs record source/request/decoded/surface
sizes so actual output can be checked on your hardware. Grid previews use 640px.
Photos are fitted without cropping; memory decoding is bounded at 4K.

Video now uses Media3's OkHttp data source with HTTPS redirects, longer timeouts,
decoder fallback, processing-status checks and detailed HTTP/track/error codes.
Google videos remain transcoded; unsupported device codecs cannot be guaranteed.
Use Retry in the viewer to refresh the selection's URLs and restart playback.

To send TV diagnostics: keep the paired phone companion open and press Send
detailed log to phone on TV (or TV log -> phone inside the viewer). The phone
receives the queued snapshot within approximately five seconds over authenticated,
certificate-pinned TLS. Alternatively press Get detailed TV log on the phone.
Copy TV log or Share TV log then lets you send it yourself. No external service
receives logs automatically. Reports contain up to 500 memory-only events plus
app version, device startup information and memory usage. Secrets, protected
media URLs, session IDs and selected filenames are excluded. A new pairing
connection replaces the old report channel. Update both apps for log transfer.
Physical video decoding and native display output still require device testing.

Selection and actual quality in v0.1.7
On phone Choose many on computer creates a Google Photos Picker session for up
to 2,000 items and exposes Copy selection link / Share selection link. Open the
link in a computer browser, sign in with the same Google account, search an album
title, select items using Google's own picker, and finish. No desktop app or
backend is needed. Links stop working after selection completes or expires.
Google controls that picker: PhotosBeam cannot inject Select all, choose a whole
existing album automatically, or keep albums synchronized.

On TV open Quality & info in the viewer. Source metadata is shown separately
from decoded resolution. Photos offer Screen (up to 4K), Full HD, or Full-size
photo file (Google download bytes, decoded/rendered up to 4K for memory safety).
Decoded photo and surface target dimensions are displayed after loading.
Video defaults to highest supported bitrate, and the panel shows the actual
decoded dimensions, delivered track format and known bitrate. Alternate quality
buttons appear only if multiple supported video tracks actually exist; a single
Google rendition cannot be turned into an original-quality/4K stream by the app.
Decoder initialization and first rendered frame are logged. Google video download
remains a transcoded rendition; no API flag selects original video bytes.

Remote controls and streaming startup in v0.1.8
The viewer's top toolbar hides after three seconds without remote/touch/phone
input. A navigation/OK press reveals it before activating a toolbar action.
Video controls also use a three-second timeout. Quality dialogs remain visible.
Phone TV controls include Previous, Next, Play, Pause, Stop (return to grid),
Start slideshow and Stop slideshow (stop automatic advance). State is refreshed
about every five seconds. Commands require the existing authenticated/pinned
pairing and have request IDs to prevent duplicate execution during retries.
Update both apps for phone controls; Google Cloud settings stay unchanged.

Video bytes still stream directly from Google's supported =dv endpoint. PhotosBeam
performs no transcoding and adds no full-file pre-download. Google supplies that
rendition; the Photos API cannot switch to original-file video streaming.
The streaming extractor omits optional MP4 tail seek-map/GOP scans that can cause
expensive restarts when the server ignores Range. Startup buffering is 750ms,
rebuffering 1500ms, with a 5–20 second forward buffer; video quality is unchanged.
Logs now include Range/Content-Range, Accept-Ranges, response timing and time to
first rendered frame. MP4 files with essential metadata at the end may still need
an initial seek, and the API/CDN can impose unavoidable latency. Full-file-byte
preservation with different delivery requires another source outside this API.

Quality & info also offers Restart with seek index / Restart with fast startup.
Fast startup skips optional fragmented-MP4 seek indexing, which can limit seeking
on such files. Use seek-index mode to restore the original indexing behavior.
This restarts the current video; neither mode transcodes or reduces quality.

Desktop link feedback and video network diagnostics in v0.1.9
Choose many on computer opens a modal immediately, with authorization/session/TV
connection progress and clear failure states. When ready, Copy link or Share link
to computer, then open it in a browser signed into the same Google account. The
app cannot launch a remote computer browser automatically. The dialog also offers
Open on this phone instead. Repeated preparation clicks are blocked while busy,
and missing access tokens or cancelled authorization no longer fail silently.

The provided v0.1.8 log stalled after Google's redirect before video response
headers, rather than waiting for decoded playback buffering. Video connections
now prefer IPv4 while keeping IPv6 fallback, use a six-second connect timeout and
20-second read-inactivity timeout, and permit one automatic loader retry. There
is no whole-video call time limit: ongoing long streams can continue normally.
DNS family counts, connection/TLS phases and destination hostnames are logged
without signed URL paths/queries or bearer headers. Byte encoding is identity
to keep range offsets unambiguous. These changes are not proof of a fix for a
Google/CDN outage or network block; share the new log if it still stalls.

Video startup and track controls in v0.2.4
The earlier TV logs show Google download header timeouts and repeated whole-file
responses to range requests, before video decoding. Google connections now try
an independent HTTP/1.1 pool after 2.5 seconds without response headers, or at
once after a connection failure. The first response owns the stream; the losing
request is cancelled and any late response body is closed. A winning HTTP/1.1
route is remembered for the current video, including later range requests.
Phone streaming is unchanged. This adds no transcoding or whole-file download.

Hold OK > More opens Viewing options with Video resolution, Video codec,
Exact supplied tracks and Automatic (best supported quality). Options come from
supported tracks reported by Media3; manual selection stays selected, and Auto
restores hardware-first ranking by resolution, frame rate and codec. Track rows
include delivered codec/profile, frame rate and bitrate when supplied.
These controls switch tracks inside the supplied media, not Google's server
rendition. A single-track MP4 has no alternate resolution/codec to switch to.
Use another source file through the phone picker for a different encoding.
https://developer.android.com/media/media3/exoplayer/track-selection
https://developers.google.com/photos/picker/guides/media-items#video-base-urls

New logs separate response-header latency, first media bytes, requested offsets,
bytes transferred, throughput, ignored Range requests and first-frame time.
Signed URLs and bearer tokens remain excluded. The fallback addresses a stalled
connection path; it cannot guarantee startup time when Google's servers delay
both paths, ignore Range, or require downloading MP4 metadata near the file end.

Whole-file Google responses in v0.2.5
The v0.2.3 device log confirms a 33 MB MP4 returned HTTP 200 to tail and backward
Range requests. The player consequently reopened/re-read the file three times;
first frame took 10–12s while hardware decoder initialization took only 72–319ms.

Google video now keeps a single sequential response in a private, temporary disk
buffer when the server ignores Range. MP4 metadata seeks, backward seeks and replay
reuse downloaded bytes. Fast-start MP4s can play before the download completes;
if essential metadata is at the end and the server ignores ranges, bytes up to
that metadata must still arrive once. Video/audio bytes are unchanged.

A real HTTP 206 response retains normal direct range streaming. The disk budget
is at most 1 GiB, leaving 128 MiB free; known larger files or insufficient space
use direct streaming. Unknown-length downloads are also capped. Read-ahead is
limited to 8 MiB beyond the furthest requested byte. The worker uses a 64 KiB
transfer buffer. Network/disk failure falls back to the normal retry path.
Buffer files are deleted when playback is left/replaced/ended. Files left by a
process kill are removed at app startup. Tokens and signed URLs stay in memory.
Phone-provided media streaming and v0.2.4 resolution/codec controls are unchanged.

Open Google Photos in Edge in v0.2.6
Phone > Photos > Google Photos > Choose in Edge creates the usual Google Picker
session and opens its selection URL specifically in Microsoft Edge (stable).
The ordinary Choose in Google Photos and Choose on computer paths remain available.
The computer-link dialog also offers Open in Edge on this phone.

Only that selection launch is directed to Edge; Android's default browser stays
unchanged. Edge must be installed and enabled in the same phone profile. If it
cannot open, PhotosBeam offers Get Edge, Try Edge again, Use default app and Close.
It does not silently send an Edge selection to another app. The same selection
link can be retried without making a new session while it remains valid.

This release adds browser routing only. It does not include the proposed bulk
selection extension or add controls to Google's Picker. Edge/Google may offer to
open another app after the initial launch; remain in the browser for webpage tools.
Google authorization still uses the existing Android authorization flow. No new
Google Cloud configuration, package IDs, permissions or signing keys are required.

TV controls, dark text and large-video startup in v0.2.7
The TV viewer has a compact direct bar: Previous, Play/Pause (or slideshow), Next,
Zoom -/+, Fit, Quality, Retry, Send log and Grid, plus photo warmth on photos.
It keeps the 3-second idle timeout. TV actions use 40dp height; viewer actions 36dp,
smaller labels/padding and clear light focus. TV root/dialogs explicitly provide
readable light foregrounds for both Material libraries; settings no longer inherit
black text. The full-screen white QR has a dark Close-button backing.

For oversized and range-supported videos, the first opened response is handed to
the player instead of closing it and making another request. Failed buffer opens
are reported before the loader's bounded retry, rather than silently repeating a
failed download. Playback errors stay visible. Preparation shows cached bytes or
large-file/end-metadata progress and offers TV storage settings. The video sample
buffer now has a 48 MiB target and 3–10s timing limits; startup remains 750ms.

The supplied log shows a 604 MB video and only about 340 MB of TV disk budget.
Google ignored requests for about 169 KB of metadata at the end and returned the
whole file; first frame took 91s, decoder initialization only 303ms. Reusing the
initial response removes an unnecessary header wait, but cannot make that server
serve byte ranges or put MP4 initialization metadata first. Freeing TV space can
allow the existing seek buffer; a local phone file supports actual range serving.

SmartTube's source selects DASH/HLS/SABR and other YouTube formats when available.
Google Photos Picker documents a download rendition (=dv), with no corresponding
DASH/HLS manifest option. Changing playback engines cannot create a missing stream.
No source encoding, resolution or frame rate is changed by this update.
