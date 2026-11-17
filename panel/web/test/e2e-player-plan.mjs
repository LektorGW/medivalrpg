// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Local browser verification: player-country plan, grading rectangle, preview/confirm and ACL.
export async function playerPlanSteps(E) {
  const {browser,step,assert,eq,page,linkLogin,go,world,DESKTOP,sleep,lastCall,shot}=E;
  world.C['c-polanie'].members[world.U.bolko.uuid]='RULER';
  world.C['c-polanie'].members[world.U.dobrawa.uuid]='ADVISOR';
  const ctx=await browser.createBrowserContext();
  const p=await page(ctx,DESKTOP,'player-plan');
  const ed=(fn,arg)=>p.evaluate((src,a)=>new Function('e','a','return ('+src+')(e,a)')(document.querySelector('canvas.plan-canvas').planEditor,a),fn.toString(),arg);
  const act=(a)=>p.evaluate(a=>document.querySelector(`[data-act="${a}"]`).click(),a);
  async function open(u) {
    await linkLogin(p,u);await go(p,'#/kraj/c-polanie/plan');
    await p.waitForSelector('canvas.plan-canvas');
  }
  await step('Plan gracza: własny władca, kanoniczne RPC, brak administracyjnego terenu i darmowych prac',async()=>{
    await open(world.U.bolko);
    assert(await p.$('.plan-toolbar [data-tool="leveling"]'),'wyrównywanie');
    assert(!await p.$('.plan-toolbar [data-tool="terrain"]'),'brak zmiany granic');
    assert(!await p.$('input[aria-label="Wyrównanie darmowo"]'),'brak darmowych prac');
    eq(lastCall('country.plan.get').params.id,'c-polanie');
    assert(!(await p.$eval('[data-act="save"]',e=>e.disabled)),'władca zapisuje');
  });
  await step('Plan gracza: zapis do realizacji wymaga potwierdzenia kosztów',async()=>{
    await act('save');await p.waitForSelector('.modal');
    assert((await p.$eval('.modal',e=>e.textContent)).includes('zasobów kraju'),'informacja o kosztach');
    assert(!lastCall('country.plan.set'),'brak zapisu przed potwierdzeniem');
    await p.evaluate(()=>[...document.querySelectorAll('.modal-foot button')].find(e=>e.textContent.includes('Zapisz plan')).click());
    await p.waitForFunction(()=>!document.querySelector('.modal'));
    await sleep(300);
    assert(lastCall('country.plan.set'),'kanoniczny zapis');
  });
  await step('Plan gracza: konkretny powód blokady i termin próby widoczne po zapisie, odświeżeniu i w podglądzie',async()=>{
    const reason='W obrysie stoją budowle graczy: minecraft:oak_planks.';
    world.planFeedback={'c-polanie':{'chata@1544,-312':{status:'czeka',reason,retryAt:Date.now()+30000}}};
    await ed(e=>e.setPlan({...e.plan,buildings:[{type:'chata',x:1544,z:-312,rotation:0,priority:1}],options:{allowDemolish:false,keepExisting:true,autoFill:false}}));
    await act('save');await p.waitForSelector('.modal');
    await p.evaluate(()=>[...document.querySelectorAll('.modal-foot button')].find(e=>e.textContent.includes('Zapisz plan')).click());
    await p.waitForFunction(reason=>document.querySelector('.plan-execution')?.textContent.includes(reason),{},reason);
    assert((await p.$eval('.plan-execution',e=>e.textContent)).includes('ponowna próba'),'retry time');
    await p.reload({waitUntil:'domcontentloaded'});
    await p.waitForFunction(reason=>document.querySelector('.plan-execution')?.textContent.includes(reason),{},reason);
    assert((await p.$eval('.plan-execution',e=>e.textContent)).includes('zablokowane: 1'),'counter');
    await shot(p,'99-player-plan-blocked-reason');
    await act('preview');await p.waitForSelector('dialog[open] .plan-preview');
    assert((await p.$eval('dialog[open]',e=>e.textContent)).includes(reason),'modal reason');
    await p.evaluate(()=>[...document.querySelectorAll('dialog[open] .modal-foot button')].find(e=>e.textContent.includes('Zamknij')).click());
    world.planFeedback=null;
  });
  await step('Wyrównywanie: przeciągnięty prostokąt, podgląd kosztu i potwierdzone zlecenie',async()=>{
    await p.click('[data-tool="leveling"]');
    await p.evaluate(()=>document.querySelector('canvas.plan-canvas').scrollIntoView({block:'center'}));
    await ed(e=>e.centerOn(1545,-308));
    await sleep(100);
    const a=await ed(e=>e.clientPoint(1540,-312)),b=await ed(e=>e.clientPoint(1545,-307));
    await p.mouse.move(a.x,a.y);await p.mouse.down();await p.mouse.move(b.x,b.y,{steps:8});await p.mouse.up();
    await sleep(100);
    const r=await ed(e=>e.levelSelection);
    assert(r && r.minX===1540 && r.maxX===1545 && r.minZ===-312 && r.maxZ===-307,JSON.stringify(r));
    assert(!(await p.$eval('[data-act="level-preview"]',e=>e.disabled)),'podgląd dostępny');
    await act('level-preview');await p.waitForSelector('.level-report');
    assert((await p.$eval('.level-report',e=>e.textContent)).includes('120 md'),'koszt');
    assert(!world.lastLevel,'preview nie zleca prac');
    await act('level-commit');await p.waitForSelector('.modal');
    assert(!world.lastLevel,'potwierdzenie przed mutacją');
    await shot(p,'99-player-plan-grading');
    await p.evaluate(()=>[...document.querySelectorAll('.modal button')].find(e=>e.textContent.includes('Zleć wyrównanie')).click());
    await p.waitForFunction(()=>!document.querySelector('.modal'));
    await sleep(350);
    assert(world.lastLevel && world.lastLevel.id==='c-polanie','zlecenie');
    eq(Object.keys(lastCall('country.plan.level').params).sort(),['id','token']);
    eq(await ed(e=>e.levelSelection),null,'zaznaczenie wyczyszczone');
  });
  await step('Wyrównywanie: cudzy teren blokuje podgląd i stare API daje czytelny komunikat',async()=>{
    await ed(e=>e.setLevelSelection({minX:1440,minZ:-310,maxX:1445,maxZ:-305}));
    assert(await p.$eval('[data-act="level-preview"]',e=>e.disabled),'cudza ziemia');
    await ed(e=>e.setLevelSelection({minX:1540,minZ:-312,maxX:1545,maxZ:-307}));
    world.levelUnsupported=true;
    await act('level-preview');
    await p.waitForFunction(()=>[...document.querySelectorAll('.toast')].some(e=>e.textContent.includes('wymaga aktualizacji pluginu')));
    world.levelUnsupported=false;
  });
  await ctx.close();
  const advisorCtx=await browser.createBrowserContext();
  const ap=await page(advisorCtx,DESKTOP,'advisor-plan');
  await step('Plan gracza: doradca czyta i wyrównuje, nie zapisuje ani nie usuwa planu',async()=>{
    await linkLogin(ap,world.U.dobrawa);await go(ap,'#/kraj/c-polanie/plan');
    await ap.waitForSelector('canvas.plan-canvas');
    assert(await ap.$eval('[data-act="save"]',e=>e.disabled),'zapis blokowany');
    assert(await ap.$('.plan-toolbar [data-tool="leveling"]'),'doradca wyrównuje');
    assert(!await ap.$('.plan-toolbar [data-tool="terrain"]'),'doradca bez granic');
  });
  await advisorCtx.close();
  const guestCtx=await browser.createBrowserContext();
  const gp=await page(guestCtx,DESKTOP,'guest-plan');
  await step('Plan gracza: obcy gracz nie widzi zakładki ani edytora',async()=>{
    await linkLogin(gp,world.U.wedrowiec);await go(gp,'#/kraj/c-polanie/plan');
    assert(!await gp.$('canvas.plan-canvas'),'brak edytora');
    assert(!await gp.$('a.tab[href$="/plan"]'),'brak zakładki');
  });
  await guestCtx.close();
}
