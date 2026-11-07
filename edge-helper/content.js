(function () {
  'use strict';
  if (location.hostname !== 'photos.google.com' || window.top !== window || document.getElementById('photosbeam-helper')) return;
  const core = globalThis.PhotosBeamCore;
  const host = document.createElement('aside'); host.id = 'photosbeam-helper';
  const shadow = host.attachShadow({mode: 'open'});
  const style = document.createElement('style');
  style.textContent = `:host{position:fixed;right:8px;top:8px;z-index:2147483646;color:#eee;font:13px system-ui;max-width:calc(100vw - 16px)}.panel{background:#15151bf2;border:1px solid #777;border-radius:12px;padding:8px;max-width:520px;box-shadow:0 3px 16px #0008}header,.buttons{display:flex;gap:5px;flex-wrap:wrap;align-items:center}button,a{font:inherit;min-height:40px;padding:7px 10px;border:1px solid #777;border-radius:8px;background:#292936;color:#fff;cursor:pointer}button:focus-visible,a:focus-visible{outline:3px solid #ffd17a}button[aria-pressed=true]{background:#57416f}p{margin:7px 2px;line-height:1.4}a{display:inline-flex;align-items:center;text-decoration:none}.small{font-size:12px;color:#ccc}header button:first-child{font-weight:700}section[hidden]{display:none}`;
  shadow.append(style);
  const panel = document.createElement('div'); panel.className = 'panel'; shadow.append(panel);
  const header = document.createElement('header'), body = document.createElement('section'); panel.append(header, body);
  const status = document.createElement('p'); status.setAttribute('role','status'); status.setAttribute('aria-live','polite');
  const summary = document.createElement('p'); summary.className = 'small';
  const buttons = document.createElement('div'); buttons.className = 'buttons'; body.append(summary, buttons, status);
  const streamResult = document.createElement('div'); body.append(streamResult);
  let anchor = null, arm = null, busy = false, watch = false, shift = false, timer, abort = new AbortController();
  let marked = null, oldOutline = '', oldOffset = '';
  const say = text => {status.textContent = text;};
  function button(parent, text, action) {const b = document.createElement('button'); b.type='button'; b.textContent=text; b.addEventListener('click',action); parent.append(b); return b;}
  button(header, 'PhotosBeam ▾', () => {body.hidden = !body.hidden;});
  const countLabel = document.createElement('span'); header.append(countLabel);
  function clearMark(){if(marked){marked.style.outline=oldOutline;marked.style.outlineOffset=oldOffset;}marked=null;}
  function state(el){if(el instanceof HTMLInputElement)return el.checked;const value=el.getAttribute(el.hasAttribute('aria-checked')?'aria-checked':'aria-selected');return value==='true'?true:value==='false'?false:null;}
  function mediaTile(el){
    let node=el;
    for(let i=0;node && i<7;i++,node=node.parentElement){
      const rect=node.getBoundingClientRect();
      if(rect.width>600 || rect.height>600)continue;
      const img=node.querySelector('img');const bg=getComputedStyle(node).backgroundImage!=='none'?getComputedStyle(node).backgroundImage:getComputedStyle(node.querySelector('[style*="background-image"]') || node).backgroundImage;
      if(img || bg && bg!=='none')return {node, image:img?.currentSrc || img?.src || bg};
    }
    return null;
  }
  function scan(){
    const result=[], seen=new Set();
    for(const el of document.querySelectorAll('input[type=checkbox],[role=checkbox][aria-checked],[role=option][aria-selected],[role=gridcell][aria-selected]')){
      const rect=el.getBoundingClientRect(), css=getComputedStyle(el), selected=state(el);
      if(!rect.width || !rect.height || css.display==='none' || css.visibility==='hidden' || selected===null || el.disabled || el.getAttribute('aria-disabled')==='true')continue;
      const tile=mediaTile(el);if(!tile)continue;
      const mediaId=tile.node.closest('[data-media-key]')?.getAttribute('data-media-key') || tile.node.getAttribute('data-id') || '';
      const image=tile.image?.replace(/=w\d+.*$/, '') || '';
      const key=mediaId+'|'+image;
      if(key==='|' || seen.has(key))continue;
      seen.add(key);result.push({el, tile:tile.node, key, selected, rect:tile.node.getBoundingClientRect()});
    }
    return result.sort((a,b)=>Math.abs(a.rect.top-b.rect.top)>3?a.rect.top-b.rect.top:a.rect.left-b.rect.left);
  }
  function refresh(){const items=scan();summary.textContent=`Loaded items: ${items.length} · Selected among loaded: ${items.filter(x=>x.selected).length}. Off-screen/unloaded items are not counted.`;countLabel.textContent=String(items.length);return items;}
  function setAnchor(item){clearMark();anchor=item.key;marked=item.tile;oldOutline=marked.style.outline;oldOffset=marked.style.outlineOffset;marked.style.outline='3px solid #ffd17a';marked.style.outlineOffset='-3px';say('Range start set. Tap Range end, then the last loaded item.');}
  async function run(keys, selected){
    if(busy)return;
    busy=true;abort=new AbortController();
    try{const n=await core.applyKeys(keys,selected,key=>scan().find(x=>x.key===key),item=>item.el.click(),abort.signal,count=>say(`${selected?'Selecting':'Clearing'}: ${count} confirmed changes…`));say(abort.signal.aborted?'Stopped. Confirmed selections are kept.':`${n} changes confirmed by Google. Use Google’s Done button when finished.`);}
    catch(error){watch=false;watchButton.setAttribute('aria-pressed','false');say(error.message);}
    finally{busy=false;refresh();}
  }
  button(buttons,'Range start',()=>{arm='start';say('Tap the first photo/video selection control.');});
  button(buttons,'Range end',()=>{if(!anchor){say('Set a range start first.');return;}arm='end';say('Tap the last loaded photo/video selection control.');});
  button(buttons,'Select loaded',()=>run(refresh().map(x=>x.key),true));
  button(buttons,'Clear loaded',()=>run(refresh().map(x=>x.key),false));
  const watchButton=button(buttons,'Select as I scroll',()=>{watch=!watch;watchButton.setAttribute('aria-pressed',String(watch));say(watch?'Selecting loaded media as you scroll. Stop at any time. Google enforces its session limit.':'Scroll selection stopped.');if(watch)run(refresh().map(x=>x.key),true);});watchButton.setAttribute('aria-pressed','false');
  const shiftButton=button(buttons,'Shift ranges',()=>{shift=!shift;shiftButton.setAttribute('aria-pressed',String(shift));say(shift?'Click a first item, then Shift-click the last loaded item.':'Native Google keyboard behavior restored.');});shiftButton.setAttribute('aria-pressed','false');
  button(buttons,'Stop',()=>{watch=false;arm=null;watchButton.setAttribute('aria-pressed','false');abort.abort();say('Stopped. Existing selections are kept.');});
  function playing(){const videos=[...document.querySelectorAll('video')].filter(v=>{const r=v.getBoundingClientRect();return r.width>20 && r.height>20;}).sort((a,b)=>Number(b.paused)-Number(a.paused) || b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight);const v=videos.find(v=>!v.paused)||videos[0];if(!v)throw new Error('Open a video in the Google Photos webpage and press Play first.');return core.videoLink({currentSrc:v.currentSrc,src:v.src,width:v.videoWidth,height:v.videoHeight,mime:v.querySelector('source')?.type});}
  button(buttons,'Playing video → phone',()=>{
    try{
      const stream=playing();streamResult.replaceChildren();
      const text=document.createElement('p');text.textContent=`Browser playback: ${stream.width} × ${stream.height}. This may differ from original quality. Sending replaces the TV selection after phone confirmation.`;streamResult.append(text);
      const link=document.createElement('a');link.textContent='Open PhotosBeam on Android';link.href=stream.intent;streamResult.append(link);
      button(streamResult,'Copy phone link',async()=>{try{await navigator.clipboard.writeText(stream.link);say('Private playback link copied. In PhotosBeam: Photos → Paste browser stream.');}catch{say('Clipboard blocked. Use the Android link instead.');}});
      say('A direct playback URL is available. The TV will stream it without the Picker download fallback. Availability must be tested on your network.');
    }catch(error){streamResult.replaceChildren();say(error.message);}
  });
  button(buttons,'Copy diagnostics',async()=>{const data={version:'0.1.0',host:location.hostname,loaded:scan().length,checkboxes:document.querySelectorAll('[role=checkbox],input[type=checkbox]').length,options:document.querySelectorAll('[aria-selected]').length,videos:document.querySelectorAll('video').length};try{await navigator.clipboard.writeText(JSON.stringify(data));say('Diagnostics copied; no URLs, filenames, dates or account data included.');}catch{say('Clipboard blocked. Loaded media controls: '+data.loaded);}});
  function onClick(event){
    if(event.composedPath().includes(host) || busy)return;
    const item=scan().find(x=>x.el===event.target || x.el.contains(event.target) || x.tile.contains(event.target));
    if(!item)return;
    if(arm || shift && event.shiftKey && anchor){
      event.preventDefault();event.stopImmediatePropagation();
      if(arm==='start'){arm=null;setAnchor(item);return;}
      arm=null;
      try{run(core.rangeKeys(refresh(),anchor,item.key),true);}catch(error){say(error.message);clearMark();anchor=null;}
    }else if(shift)setAnchor(item);
  }
  document.addEventListener('click',onClick,true);
  const observer=new MutationObserver(()=>{clearTimeout(timer);timer=setTimeout(()=>{const items=refresh();if(watch && !busy && !document.hidden)run(items.map(x=>x.key),true);},350);});
  observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['aria-checked','aria-selected','src','data-media-key']});
  button(header,'Close',()=>{watch=false;abort.abort();observer.disconnect();clearTimeout(timer);document.removeEventListener('click',onClick,true);clearMark();host.remove();});
  document.documentElement.append(host);refresh();say('Use range controls or Select loaded. Finish with Google’s own Done button. No account tokens or cookies are read.');
})();
