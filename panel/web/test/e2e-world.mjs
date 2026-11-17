// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
export async function worldSteps(E){
  const {browser,step,assert,eq,page,linkLogin,go,shot,text,lastCall,world,DESKTOP,MOBILE}=E;
  const context=await browser.createBrowserContext(),p=await page(context,DESKTOP,"skarb-swiata");
  await step("Skarb Świata: osobisty zarządca widzi pulę, pokrycie monet i trzy budżety",async()=>{
    await linkLogin(p,world.U.maciej);await go(p,"#/skarbswiata");
    const body=await text(p,"#main");for(const label of ["Skarb Świata","Cała pula","Rezerwa pod Twoją kontrolą","Kupcy","Obywatele NPC","Pozostałe wypłaty"])assert(body.includes(label),label);
    assert(await p.$('a[href="#/skarbswiata"]'),"pozycja menu");await shot(p,"112-skarb-swiata");
  });
  await step("Skarb Świata: przydział jest jawnym przelewem do wybranego budżetu",async()=>{
    await p.evaluate(()=>[...document.querySelectorAll("#main section")].find(s=>s.querySelector("h3")?.textContent==="Kupcy").querySelector("button").click());
    await p.waitForSelector('.modal input');await p.type('.modal input','100');
    await p.evaluate(()=>[...document.querySelectorAll(".modal button")].find(b=>b.textContent.trim()==="Przelej").click());
    await p.waitForFunction(()=>!document.querySelector('.modal'));
    await p.waitForFunction(()=>document.querySelector('#main')?.textContent.replace(/\s/g,'').includes('20001'));
    eq(lastCall("world.allocate").params,{account:"world:merchant",amount:"100"});
  });
  await step("Skarb Świata: finansowanie kolei przenosi rezerwę na konto firmy",async()=>{
    await p.evaluate(()=>[...document.querySelectorAll("#main button")].find(b=>b.textContent==="Zasil przewoźnika").click());
    await p.waitForSelector('.modal input');await p.type('.modal input','25000');
    const saved=p.waitForResponse(r=>r.url().endsWith('/api/rpc')&&r.request().postData()?.includes('world.allocate'));
    await p.evaluate(()=>[...document.querySelectorAll('.modal button')].find(b=>b.textContent==='Przelej').click());await saved;
    await p.waitForFunction(()=>!document.querySelector('.modal'));
    eq(lastCall('world.allocate').params,{account:'kolej:przewoznik',amount:'25000'});
  });
  await step("Skarb Świata: 390 px bez poziomego przewijania",async()=>{
    await p.setViewport(MOBILE);await go(p,"#/skarbswiata");assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),"overflow");await shot(p,"113-skarb-swiata-390");
  });
  await context.close();
  const other=await browser.createBrowserContext(),q=await page(other,DESKTOP,"skarb-inny-admin");
  await step("Skarb Świata: drugi administrator nie otrzymuje zarządu",async()=>{
    world.U.wedrowiec.admin=true;await linkLogin(q,world.U.wedrowiec);assert(!(await q.$('a[href="#/skarbswiata"]')),"brak pozycji menu");
    await go(q,"#/skarbswiata");assert((await text(q,"#main")).includes("Brak dostępu"),"odmowa");
  });
  await other.close();
}
