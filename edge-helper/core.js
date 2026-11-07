/* PhotosBeam helper core. No network, cookies, credentials or account API access. */
(function (root) {
  'use strict';
  const MAX_BATCH = 2000;
  function rangeKeys(items, first, last) {
    const a = items.findIndex(x => x.key === first), b = items.findIndex(x => x.key === last);
    if (a < 0 || b < 0) throw new Error('An anchor is no longer loaded. Set the range again.');
    const result = items.slice(Math.min(a, b), Math.max(a, b) + 1).map(x => x.key);
    if (result.length > MAX_BATCH) throw new Error('Choose a range of at most 2,000 loaded items.');
    return result;
  }
  function googleMediaUrl(value) {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') ||
        !(host.endsWith('.googlevideo.com') || host.endsWith('.googleusercontent.com'))) throw new Error('Only HTTPS Google media playback URLs are supported.');
    if (/\.(m3u8|mpd)$/i.test(url.pathname) || url.searchParams.has('range')) throw new Error('This is a manifest or partial segment, not a transferable complete video stream.');
    if (url.href.length > 16000) throw new Error('Playback URL is too long.');
    url.hash = '';
    return url.href;
  }
  function videoLink(video) {
    const source = video.currentSrc || video.src;
    if (!source || source.startsWith('blob:')) throw new Error('Google is using an in-browser blob/segmented player. This URL cannot be sent to the TV. No download fallback was started.');
    const url = googleMediaUrl(source);
    const mime = /webm/i.test(video.mime || '') || /\.webm$/i.test(new URL(url).pathname) ? 'video/webm' : 'video/mp4';
    const params = new URLSearchParams({url, mime, width: String(video.width || 0), height: String(video.height || 0)});
    return {link: 'photosbeam://stream?' + params, intent: 'intent://stream?' + params + '#Intent;scheme=photosbeam;package=dev.personal.photostv.phone;end', width: video.width || 0, height: video.height || 0};
  }
  async function applyKeys(keys, selected, find, click, signal, changed = () => {}) {
    if (keys.length > MAX_BATCH) throw new Error('At most 2,000 items per operation.');
    let count = 0;
    for (const key of keys) {
      if (signal.aborted) break;
      let item = find(key);
      if (!item) throw new Error('The page unloaded or changed an item. Selection stopped; choose a smaller loaded range.');
      if (item.selected === selected) continue;
      click(item);
      const deadline = Date.now() + 1800;
      while (!signal.aborted && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 60));
        item = find(key);
        if (item && item.selected === selected) break;
      }
      if (signal.aborted) break;
      if (!item || item.selected !== selected) throw new Error('Google did not confirm the selection. Stopped without clicking other controls.');
      count++; changed(count);
    }
    return count;
  }
  const api = {MAX_BATCH, rangeKeys, googleMediaUrl, videoLink, applyKeys};
  root.PhotosBeamCore = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(globalThis);
