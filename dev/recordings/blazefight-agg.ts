import { readFileSync } from "node:fs";
const rows = readFileSync(process.argv[2], "utf8").trim().split("\n").map((l) => JSON.parse(l));
const stats = (a: number[]) => { if (!a.length) return null; const s=[...a].sort((x,y)=>x-y); const q=(p:number)=>+s[Math.min(s.length-1,Math.floor(p*s.length))].toFixed(2); return {n:a.length,p10:q(.1),p25:q(.25),med:q(.5),p75:q(.75),p90:q(.9),max:q(.999)}; };
const pool = (k: string) => rows.flatMap((r) => r.raw[k]);
for (const k of ["blazeSeenDist","firstShotDelay","fbDist","fbVolleySizes","volleyGaps","shotGapsInVolley","hoverDy","dist2d","speed","hpSteps","nearBlazeAtDamage"]) console.log(k, JSON.stringify(stats(pool(k))));
const by: Record<string, number[]> = {};
for (const r of rows) for (const [k, v] of Object.entries(r.raw.dmgByType)) (by[k] ??= []).push(...(v as number[]));
for (const [k, v] of Object.entries(by)) console.log("dmg src", k, JSON.stringify(stats(v)));
console.log("files", rows.length, "with blazes", rows.filter((r) => r.blazes > 0).length, "blazes", rows.reduce((a, r) => a + r.blazes, 0), "kills", rows.reduce((a, r) => a + r.blazeKills, 0), "attacks", rows.reduce((a, r) => a + r.attacks, 0), "swings", rows.reduce((a, r) => a + r.swings, 0));
