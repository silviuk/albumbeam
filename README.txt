AlbumBeam — personal Google Photos session viewer

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
Both apps are named AlbumBeam. Launch the TV app, then open the companion and
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
never disable certificate validation. Use End / Pair again to regenerate.
Guest Wi-Fi, client isolation, VPNs and changing network addresses can interrupt
pairing. Use the same ordinary LAN and regenerate the QR after network changes.
403/auth errors: verify API enablement, OAuth package, signing SHA-1, scope and
test-user account. Retry after connectivity returns. Use Skip for failed media.
Process death loses the session. Reopen both apps and pair/select again.

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
AlbumBeam is an independent project and is not affiliated with Google.

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
Android logcat under AlbumBeam. They exclude Google tokens, QR secrets, media
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
Google controls that picker: AlbumBeam cannot inject Select all, choose a whole
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
