// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import test from "node:test";
import assert from "node:assert/strict";
import { planAccess, planMethods, levelRectangle, levelSelectionError, planExecution } from "../public/js/plan/access.js";

test("ruler can edit own player plan, advisor can read and grade, citizen has no editor", () => {
  const c={id:"mine",npc:false};
  assert.deepEqual(planAccess(c,"RULER"),{read:true,write:true,level:true,territory:false,free:false});
  assert.equal(planAccess(c,"ADVISOR").read,true);
  assert.equal(planAccess(c,"ADVISOR").write,false);
  assert.equal(planAccess(c,"ADVISOR").level,true);
  assert.equal(planAccess(c,"CITIZEN").read,false);
});
test("NPC manager cannot gain player-country powers or free grading", () => {
  const roles={canNpcCountry:()=>true};
  assert.equal(planAccess({id:"player",npc:false},null,roles).write,false);
  assert.equal(planAccess({id:"npc",npc:true},null,roles).write,true);
  assert.equal(planAccess({id:"npc",npc:true},null,roles).free,false);
  assert.equal(planAccess({id:"player",npc:false},null,{admin:true}).territory,false);
});
test("server capabilities override inferred UI rank and legacy NPC calls stay compatible", () => {
  assert.equal(planAccess({raw:{access:{planWrite:false}}},"RULER").write,false);
  assert.equal(planMethods(true).get,"country.plan.get");
  assert.equal(planMethods(false).set,"admin.npc.setPlan");
});
test("grading rectangle is inclusive, reversible, bounded and block-based", () => {
  assert.deepEqual(levelRectangle({x:4,z:7},{x:0,z:0}),{minX:0,minZ:0,maxX:4,maxZ:7});
  assert.doesNotThrow(()=>levelRectangle({x:0,z:0},{x:63,z:63}));
  assert.throws(()=>levelRectangle({x:0,z:0},{x:64,z:63}),/4096/);
  assert.throws(()=>levelRectangle({x:1.5,z:0},{x:2,z:2}));
});
test("grading refuses foreign chunks and protected areas, including negative chunk coordinates", () => {
  const r=levelRectangle({x:-2,z:1},{x:2,z:2});
  assert.match(levelSelectionError(r,new Set(["0,0"])),/ziemi/);
  const land=new Set(["-1,0","0,0"]);
  assert.equal(levelSelectionError(r,land),null);
  assert.match(levelSelectionError(r,land,[{minCX:0,maxCX:0,minCZ:0,maxCZ:0}]),/chroniony/);
});
import { PlanEditor } from "../public/js/plan/editor.js";
test("territory rectangle rejects distant island spans before allocating chunk keys",()=>{
  const messages=[];
  const editor={tool:"terrain",opts:{terrShape:"rect"},toast:m=>messages.push(m),history:{push:()=>assert.fail("must reject before recording or painting")}};
  PlanEditor.prototype.toolUp.call(editor,{moved:true,rect:{x0:-29_999_000,z0:-29_999_000,x1:29_999_000,z1:29_999_000}},{x:29_999_000,z:29_999_000},{});
  assert.match(messages[0],/4096/);
});
test("execution feedback scopes blocking reasons and retry times to individual saved-plan orders",()=>{
  const retryAt=Date.now()+30_000;
  const data=planExecution({failure:"stary błąd innego planisty",orders:[
    {type:"chata",status:"czeka",reason:"Budowle graczy: minecraft:oak_planks",retryAt},
    {type:"dom",status:"odrzucony",reason:"Strefa nie_ruszac",retryAt:null},
    {type:"studnia",status:"zlecony"},{type:"koszary",status:"czeka"},null]});
  assert.equal(data.blocked.length,2);
  assert.equal(data.blocked[0].retryAt,retryAt);
  assert.equal(data.blocked[1].retryAt,null);
  assert.equal(data.ordered,1);
  assert.equal(data.waiting,1);
  assert.equal(JSON.stringify(data).includes("stary błąd"),false);
  assert.deepEqual(planExecution(null),{orders:[],blocked:[],ordered:0,waiting:0});
});
