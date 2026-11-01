// blazefight.ts <run.edn.zst>...: per file, what a real blaze fight looks like from the packets.
// Blaze = type 14, small fireball = 118. Bot position from its move-player-pos-rot sends.
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

type Ent = { type: number; x: number; y: number; z: number; first: number; hp?: number; sawBot?: number; fired?: number };
const stats = (a: number[]) => {
  if (!a.length) return null;
  const s = [...a].sort((x, y) => x - y);
  const q = (p: number) => +s[Math.min(s.length - 1, Math.floor(p * s.length))].toFixed(2);
  return { n: a.length, min: q(0), p25: q(0.25), med: q(0.5), p75: q(0.75), max: q(0.999) };
};

async function one(file: string) {
  const z = spawn("zstd", ["-dc", file]);
  const rl = createInterface({ input: z.stdout });
  let t = 0, myId = -1, dim = "", bx = 0, by = 0, bz = 0, hasBot = false;
  const ents = new Map<number, Ent>();
  const blazeSeenDist: number[] = [], firstShotDelay: number[] = [], hoverDy: number[] = [], dist2d: number[] = [];
  const fbDist: number[] = [], fbVolleySizes: number[] = [], volleyGaps: number[] = [], shotGapsInVolley: number[] = [];
  const dmgByType: Record<string, number[]> = {};
  let prevHp = 20, lastSrc = -1, lastSrcT = 0;
  const hitsPerBlazeHpDrop: number[] = [];
  const lastShot = new Map<number, { t: number; n: number; lastT: number }>();
  const attackT: number[] = [];
  const blazeHpSeen = new Map<number, number[]>();
  let fireTicks = 0, attacks = 0, swings = 0;
  const nearBlazeAtDamage: number[] = [];
  const speed: number[] = [];
  const lastPos = new Map<number, { x: number; y: number; z: number; t: number }>();
  let inNether = false;
  const dmgSrcCount: Record<string, number> = {};
  const netherPos: number[][] = [];
  let lastPosT = 0;
  for await (const l of rl) {
    if (l.startsWith("#:event{:kind :tick")) {
      const m = l.match(/:now (\d+)/);
      if (m) t = Number(m[1]);
      continue;
    }
    if (l.startsWith("#:record{:effects")) {
      const m = l.match(/:packet\/name :move-player-pos-rot, :x ([-0-9.eE]+), :y ([-0-9.eE]+), :z ([-0-9.eE]+)/);
      if (m) { bx = +m[1]; by = +m[2]; bz = +m[3]; hasBot = true; if (inNether && t - lastPosT > 5000) { netherPos.push([bx, bz]); lastPosT = t; } }
      if (l.includes(":packet/name :attack,")) attacks++;
      if (l.includes(":packet/name :swing,")) swings++;
      continue;
    }
    if (!inNether) {
      if (l.includes(":packet/name :respawn") && l.includes("the_nether")) inNether = true;
      else if (l.includes(":packet/name :login,") ) { const m = l.match(/:entity-id (\d+)/); if (m) myId = +m[1]; }
      // fortress start: the login is already in the nether
      if (l.includes(":packet/name :login,") && l.includes(':dimension "minecraft:the_nether"')) inNether = true;
      if (!inNether) continue;
    }
    if (l.includes(":packet/name :login,")) { const m = l.match(/:entity-id (\d+)/); if (m) myId = +m[1]; }
    if (l.includes(":packet/name :remove-entities")) {
      const m = l.match(/:ids \[([0-9 ]*)\]/);
      if (m) for (const id of m[1].split(" ")) if (id) ents.delete(+id);
      continue;
    }
    if (l.includes(":packet/name :add-entity")) {
      const ty = l.match(/:type (\d+),/), id = l.match(/:entity-id (\d+)/);
      if (!ty || !id) continue;
      const type = +ty[1];
      const x = +(l.match(/:x ([-0-9.eE]+)/)?.[1] ?? 0), y = +(l.match(/:y ([-0-9.eE]+)/)?.[1] ?? 0), zz = +(l.match(/:z ([-0-9.eE]+)/)?.[1] ?? 0);
      if (type === 14) {
        ents.set(+id[1], { type, x, y, z: zz, first: t });
        if (hasBot) { blazeSeenDist.push(Math.hypot(x - bx, y - by, zz - bz)); ents.get(+id[1])!.sawBot = t; }
      } else if (type === 118 || type === 52) {
        const owner = +(l.match(/:data (\d+)/)?.[1] ?? -1);
        const o = ents.get(owner);
        if (o && hasBot) {
          fbDist.push(Math.hypot(x - bx, y - by, zz - bz));
          const e = lastShot.get(owner);
          if (!e || t - e.lastT > 2000) {
            if (e) { fbVolleySizes.push(e.n); volleyGaps.push((t - e.t) / 1000); }
            else if (o.sawBot !== undefined) firstShotDelay.push((t - o.sawBot) / 1000);
            lastShot.set(owner, { t, n: 1, lastT: t });
          } else { shotGapsInVolley.push((t - e.lastT) / 1000); e.n++; e.lastT = t; }
        }
      }
      continue;
    }
    if (l.includes(":packet/name :move-entity-pos") || l.includes(":packet/name :entity-position-sync")) {
      const id = l.match(/:entity-id (\d+)/);
      if (!id) continue;
      const e = ents.get(+id[1]);
      if (!e) continue;
      if (l.includes("entity-position-sync")) {
        e.x = +(l.match(/:x ([-0-9.eE]+)/)?.[1] ?? e.x); e.y = +(l.match(/:y ([-0-9.eE]+)/)?.[1] ?? e.y); e.z = +(l.match(/:z ([-0-9.eE]+)/)?.[1] ?? e.z);
      } else {
        const dx = +(l.match(/:dx (-?\d+)/)?.[1] ?? 0), dy = +(l.match(/:dy (-?\d+)/)?.[1] ?? 0), dz = +(l.match(/:dz (-?\d+)/)?.[1] ?? 0);
        e.x += dx / 4096; e.y += dy / 4096; e.z += dz / 4096;
      }
      if (hasBot) {
        const d = Math.hypot(e.x - bx, e.z - bz);
        if (d < 24) { hoverDy.push(e.y - by); dist2d.push(d); }
        const lp = lastPos.get(+id[1]);
        if (lp && t - lp.t >= 400) {
          speed.push(Math.hypot(e.x - lp.x, e.y - lp.y, e.z - lp.z) / ((t - lp.t) / 1000));
          lastPos.set(+id[1], { x: e.x, y: e.y, z: e.z, t });
        } else if (!lp) lastPos.set(+id[1], { x: e.x, y: e.y, z: e.z, t });
      }
      continue;
    }
    if (l.includes(":packet/name :set-entity-data")) {
      const id = l.match(/:entity-id (\d+)/);
      if (!id || !ents.has(+id[1])) continue;
      const h = l.match(/:metadata \{[^}]*?\b9 ([0-9.]+)/);
      if (h) { const a = blazeHpSeen.get(+id[1]) ?? []; a.push(+h[1]); blazeHpSeen.set(+id[1], a); }
      continue;
    }
    if (l.includes(":packet/name :damage-event")) {
      const id = l.match(/:entity-id (\d+)/), st = l.match(/:source-type (\d+)/);
      if (id && +id[1] === myId && st) {
        lastSrc = +st[1]; lastSrcT = t;
        dmgSrcCount[st[1]] = (dmgSrcCount[st[1]] ?? 0) + 1;
        let nb = 0;
        for (const e of ents.values()) if (e.type === 14 && Math.hypot(e.x - bx, e.z - bz) < 16) nb++;
        nearBlazeAtDamage.push(nb);
      }
      continue;
    }
    if (l.includes(":packet/name :set-health")) {
      const h = l.match(/:health ([0-9.]+)/);
      if (h) {
        const v = +h[1];
        if (v < prevHp) {
          const k = String(t - lastSrcT < 300 ? lastSrc : "?");
          (dmgByType[k] ??= []).push(+(prevHp - v).toFixed(2));
        }
        prevHp = v;
      }
    }
  }
  const blazeKills = [...blazeHpSeen.values()].filter((a) => a.some((x) => x <= 0)).length;
  const hpSteps: number[] = [];
  for (const a of blazeHpSeen.values()) for (let i = 1; i < a.length; i++) if (a[i] < a[i - 1]) hpSteps.push(+(a[i - 1] - a[i]).toFixed(2));
  return {
    file, blazes: [...ents.values()].filter((e) => e.type === 14).length, attacks, swings, blazeKills,
    blazeSeenDist: stats(blazeSeenDist), firstShotDelayS: stats(firstShotDelay), fbDistFromBot: stats(fbDist),
    fbPerVolley: stats(fbVolleySizes), volleyGapS: stats(volleyGaps), shotGapInVolleyS: stats(shotGapsInVolley),
    hoverDy: stats(hoverDy), dist2d: stats(dist2d), blazeSpeed: stats(speed), blazeHpStep: stats(hpSteps),
    dmgSrcCount, dmgBySource: Object.fromEntries(Object.entries(dmgByType).map(([k, v]) => [k, stats(v)])),
    nearBlazeAtDamage: stats(nearBlazeAtDamage),
    netherPos: netherPos.length ? { n: netherPos.length, x: stats(netherPos.map((p) => p[0])), z: stats(netherPos.map((p) => p[1])) } : null,
    raw: { blazeSeenDist, firstShotDelay, fbDist, fbVolleySizes, volleyGaps, shotGapsInVolley, hoverDy, dist2d, speed, hpSteps, nearBlazeAtDamage, dmgByType },
  };
}

for (const f of process.argv.slice(2)) console.log(JSON.stringify(await one(f)));
