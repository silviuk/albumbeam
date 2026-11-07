const {chromium}=require('playwright');const assert=require('node:assert/strict');const path=require('node:path');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  for(const mobile of [false,true]){
   const context=await browser.newContext({viewport:mobile?{width:412,height:915}:{width:1280,height:800},isMobile:mobile,hasTouch:mobile});
   const page=await context.newPage();let destructive=0;
   await page.route('**/*',route=>route.request().resourceType()==='document'?route.fulfill({contentType:'text/html',body:`<!doctype html><style>.grid{display:grid;grid-template-columns:repeat(3,90px);gap:4px;margin-top:440px}.tile{width:90px;height:90px;position:relative}.tile img{width:90px;height:90px}.tile button{position:absolute;left:0;top:0;width:28px;height:28px}</style><button id="delete">Delete</button><div class="grid"></div><script>window.addTile=function(i){const tile=document.createElement('div');tile.className='tile';tile.dataset.mediaKey='media-'+i;const img=document.createElement('img');img.src='https://lh3.googleusercontent.com/fixture-'+i+'=w90';tile.append(img);const control=document.createElement('button');control.setAttribute('role','checkbox');control.setAttribute('aria-checked','false');control.id='item-'+i;control.textContent='✓';tile.append(control);tile.querySelector('button').onclick=e=>e.currentTarget.setAttribute('aria-checked',String(e.currentTarget.getAttribute('aria-checked')!=='true'));document.querySelector('.grid').append(tile)};for(let i=0;i<6;i++)addTile(i);</script>`}):route.abort());
   await page.goto('https://photos.google.com/picker-fixture');
   await page.exposeFunction('destructive',()=>destructive++);await page.evaluate(()=>document.querySelector('#delete').onclick=()=>window.destructive());
   await page.addScriptTag({path:path.resolve('edge-helper/core.js')});await page.addScriptTag({path:path.resolve('edge-helper/content.js')});
   const click=async locator=>mobile?locator.tap():locator.click();const helper=page.locator('#photosbeam-helper');
   await click(helper.getByRole('button',{name:'Range start',exact:true}));await click(page.locator('#item-1'));
   await click(helper.getByRole('button',{name:'Range end',exact:true}));await click(page.locator('#item-4'));
   await page.waitForFunction(()=>[1,2,3,4].every(i=>document.querySelector('#item-'+i).getAttribute('aria-checked')==='true'));
   assert.equal(await page.locator('[role=checkbox][aria-checked=true]').count(),4);
   await click(helper.getByRole('button',{name:'Clear loaded',exact:true}));await page.waitForFunction(()=>!document.querySelector('[role=checkbox][aria-checked=true]'));
   await page.waitForTimeout(150);
   if(!mobile){
    await click(helper.getByRole('button',{name:'Shift ranges',exact:true}));await click(page.locator('#item-0'));await page.locator('#item-2').click({modifiers:['Shift']});
    await page.waitForFunction(()=>[0,1,2].every(i=>document.querySelector('#item-'+i).getAttribute('aria-checked')==='true'));
    await page.waitForTimeout(150);
   }
   await click(helper.getByRole('button',{name:'Select as I scroll',exact:true}));await page.waitForFunction(()=>document.querySelectorAll('[role=checkbox][aria-checked=true]').length===6);
   await page.evaluate(()=>window.addTile(6));await page.waitForFunction(()=>document.querySelector('#item-6').getAttribute('aria-checked')==='true');
   await page.waitForTimeout(150);await click(helper.getByRole('button',{name:'Stop',exact:true}));await page.evaluate(()=>window.addTile(7));await page.waitForTimeout(600);assert.equal(await page.locator('#item-7').getAttribute('aria-checked'),'false');
   await click(helper.getByRole('button',{name:'Range start',exact:true}));await click(page.locator('#item-0'));await page.evaluate(()=>document.querySelector('#item-0').closest('.tile').remove());
   await click(helper.getByRole('button',{name:'Range end',exact:true}));await click(page.locator('#item-7'));await helper.getByRole('status').filter({hasText:'no longer loaded'}).waitFor();
   await click(helper.getByRole('button',{name:'Playing video → phone',exact:true}));await helper.getByRole('status').filter({hasText:'press Play first'}).waitFor();
   await page.evaluate(()=>{const v=document.createElement('video');v.style='width:200px;height:100px';Object.defineProperty(v,'currentSrc',{value:'blob:https://photos.google.com/private'});document.body.append(v)});
   await click(helper.getByRole('button',{name:'Playing video → phone',exact:true}));await helper.getByRole('status').filter({hasText:'cannot be sent'}).waitFor();assert.equal(await helper.locator('a').count(),0);
   assert.equal(destructive,0);await click(helper.getByRole('button',{name:'Close',exact:true}));assert.equal(await helper.count(),0);
   await context.close();console.log(`PASS ${mobile?'touch':'desktop'} range, selection, scroll loading, stop, unloaded anchors, blob rejection and no destructive clicks`);
  }
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exit(1)});
