const {test}=require('node:test');const assert=require('node:assert/strict');
const core=require('../core.js');
const media='https://r1.googlevideo.com/videoplayback?sig=private%2Fsecret';
test('forward and backward ranges include both anchors',()=>{
 const items=['a','b','c','d'].map(key=>({key}));assert.deepEqual(core.rangeKeys(items,'c','a'),['a','b','c']);
 assert.throws(()=>core.rangeKeys(items,'gone','a'));assert.throws(()=>core.rangeKeys(Array.from({length:2001},(_,i)=>({key:i})),0,2000));
});
test('only complete progressive Google HTTPS URLs can be handed off',()=>{
 assert.equal(core.googleMediaUrl(media),media);
 for(const url of ['blob:https://photos.google.com/secret','http://r1.googlevideo.com/v','https://googlevideo.com.evil.org/v','https://evilgooglevideo.com/v','https://name:secret@r1.googlevideo.com/v','https://r1.googlevideo.com:444/v','https://r1.googlevideo.com/v.mpd','https://r1.googlevideo.com/v.m3u8','https://r1.googlevideo.com/v?range=0-200'])assert.throws(()=>core.googleMediaUrl(url));
 const stream=core.videoLink({currentSrc:media,width:3840,height:2160});assert.equal(new URL(stream.link).searchParams.get('url'),media);assert.match(stream.intent,/package=dev.personal.photostv.phone/);
 assert.throws(()=>core.videoLink({currentSrc:'blob:https://photos.google.com/a'}));
});
test('only mismatched states are clicked and changes must be confirmed',async()=>{
 const items=new Map([['a',{selected:false}],['b',{selected:true}]]);let clicks=0;
 assert.equal(await core.applyKeys(['a','b'],true,k=>items.get(k),x=>{clicks++;x.selected=true},new AbortController().signal),1);assert.equal(clicks,1);
 await assert.rejects(core.applyKeys(['gone'],true,k=>items.get(k),()=>{},new AbortController().signal),/unloaded/);
});
test('cancel stops serial clicks',async()=>{
 const stop=new AbortController();let clicks=0;
 const item={selected:false};await core.applyKeys(['a','b'],true,()=>item,()=>{clicks++;stop.abort()},stop.signal);assert.equal(clicks,1);
});
