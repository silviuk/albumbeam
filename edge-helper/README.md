# PhotosBeam Selection Helper 0.1.0

An experimental toolbar on Google Photos for **loaded-item ranges**, **Select loaded**, and **Select as I scroll**. Also attempts to hand off a currently playing browser video to PhotosBeam 0.2.8+.

## Android: regular Microsoft Edge

This edition uses **Tampermonkey**, which [officially supports Edge on Android](https://www.tampermonkey.net/faq.php?q=Q406). It does not require changing your default browser or installing Edge Canary.

1. In Edge, open **Extensions** and install **Tampermonkey**, or use the official link on the [Tampermonkey browser support page](https://www.tampermonkey.net/faq.php?q=Q406). Its Edge extension ID is `iikmkjmpaadaobahmlepeloendndfphd`.
2. Open [Install PhotosBeam userscript](https://raw.githubusercontent.com/silviuk/albumbeam/main/edge-helper/photosbeam-helper.user.js) in Edge and approve installation in Tampermonkey. If Edge displays source instead, open Tampermonkey's Dashboard → Utilities → Install from URL and paste that link. Allow user scripts/site access if your browser requests it; menu names vary by build.
3. Return to PhotosBeam on your phone and use **Choose in Edge**. This opens only that selection in Edge, leaving your default browser unchanged. Reload an already open Google Photos tab after installing the helper. Enable Tampermonkey for that site if its toolbar does not appear.
4. Tap **Range start**, then the first photo/video selection control. Tap **Range end**, then the last **loaded** item. Or use **Select loaded** / **Select as I scroll** and scroll manually.
5. Finish with Google's own **Done** button. The helper never submits a selection automatically.

Regular Chrome on Android does not support this extension/userscript installation. The standalone ZIP below is for desktop Edge, not a promise of custom-extension installation on mobile Edge.

## Desktop Edge

Download `photosbeam-edge-helper-v0.1.0.zip` from [the v0.2.8 release](https://github.com/silviuk/albumbeam/releases/tag/v0.2.8), extract it, open `edge://extensions`, enable **Developer mode**, and **Load unpacked** the extracted folder. Reload Google Photos. Use the link from PhotosBeam's **Choose on computer** on the same Google account.

Enable **Shift ranges**, click a first item, then Shift-click the last loaded item. Leave that option off to preserve Google's native keyboard behavior. Range controls also work with a mouse. Install only one edition (userscript or standalone extension) per browser profile.

## What “loaded” means

Google virtualizes its photo grids. Only items with recognizable media selection controls currently in the page are included. **Select loaded is not Select entire album or timeline**. Search/open the intended album/date first. **Select as I scroll** adds newly loaded items as you manually scroll; it does not auto-scroll, fetch hidden library data, or bypass Google's 2,000-item Picker session limit. Day/week header controls are not synthesized. The helper stops if Google changes/unloads an item or does not confirm a click; existing selections are preserved. Press **Stop** before leaving the intended group.

If the count stays zero, that Google page/build is not exposing supported controls. Do not assume anything was selected. Use the normal Google controls and send **Copy diagnostics** output (no URLs, names or account data). This first version is tested on simulated desktop/touch grids, not a signed-in Google account; Google's DOM may require adaptations.

## Experimental playing-video handoff

Open a video on the **Google Photos webpage**, play it, then choose **Playing video → phone**. A standalone, progressive HTTPS Google MP4/WebM URL may be transferable. On Android, tap **Open PhotosBeam on Android**. On desktop, use **Copy phone link**, transfer that private link to your phone, then **Photos → Paste browser stream**. Review the host/resolution and confirm **Replace TV selection**.

PhotosBeam sends the URL over its paired connection; the TV fetches the video directly from Google. The helper does not download the file, proxy through a server, import cookies/OAuth tokens, or transcode. It reports the browser's decoded resolution, **not original resolution**. Google may provide a lower-quality browser rendition. The signed playback link is private and expires; avoid posting it or including it in logs.

**This does not unlock a universal Google streaming API.** `blob:` / MSE players, separate audio/video segments, DASH/HLS manifests, and partial byte-range segment URLs are rejected. Cookie-bound, IP-bound or expired URLs may fail on the TV. The helper does not reconstruct streams from internal network requests. There is no automatic fallback to the Picker download endpoint. If a transferable URL is unavailable, use a **local original file** through the phone for reliable original-file playback; Google cloud providers can download/convert files before exposing them.

## Streaming-only playback

PhotosBeam 0.2.8 uses **streaming only by default**. Some Picker videos are MP4s whose playback index is at the end while Google's download endpoint ignores byte seeks. That combination cannot start immediately through the supported API. The TV now stops an ignored nonzero seek before reading its full response. **Quality → Allow full-file preparation (slow)** explicitly enables the older disk-buffer route for Picker videos. Browser handoff never enables it. Streaming may still involve normal network read-ahead and buffering; the app cannot force Google to provide another codec/resolution that the source does not contain.

## Build and tests

`python3 edge-helper/build.py` regenerates the userscript and desktop ZIP from `core.js` + `content.js`. No remote scripts or build dependencies are included in the extension. Only `https://photos.google.com/*` pages match; there are no cookie, download, account or background permissions.

`node --test edge-helper/tests/core.test.cjs` tests ranges, confirmed selection, cancellation and URL boundaries. `node edge-helper/tests/browser.cjs` uses Playwright Chromium for mouse/touch fixture interactions, rerenders, scroll loading and unsupported video behavior. CI installs pinned Playwright and Chromium. These checks do not establish compatibility with Google's current authenticated DOM or physical Android Edge/TV hardware.
