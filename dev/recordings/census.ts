// census.ts <run.edn.zst>...: what kinds of entities a race's recordings show in the Nether, per
// recording: files that saw the kind, entities added, and the most alive at once. Run on a runner
// (probe.yml) over a downloaded race artifact; node runs it as it is (node census.ts ...).
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { createInterface } from "node:readline";

const names = new Map<number, string>();
for (const m of readFileSync("resources/clojurecraft/entity-types.edn", "utf8").matchAll(/:([a-z_0-9]+) (\d+)/g)) {
  names.set(Number(m[2]), m[1]);
}

type Tally = { files: number; added: number; peak: number };
const total = new Map<string, Tally>();

async function one(file: string) {
  const z = spawn("zstd", ["-dc", file]);
  const rl = createInterface({ input: z.stdout });
  let nether = false;
  const alive = new Map<number, string>();
  const peak = new Map<string, number>();
  const added = new Map<string, number>();
  for await (const l of rl) {
    if (!nether) {
      if (l.includes(":packet/name :respawn") && l.includes("the_nether")) nether = true;
      if (l.includes(":packet/name :login,") && l.includes('"minecraft:the_nether"')) nether = true;
      continue;
    }
    if (l.includes(":packet/name :add-entity")) {
      const ty = l.match(/:type (\d+),/);
      const id = l.match(/:entity-id (\d+)/);
      if (!ty || !id) continue;
      const kind = names.get(Number(ty[1])) ?? `type-${ty[1]}`;
      alive.set(Number(id[1]), kind);
      added.set(kind, (added.get(kind) ?? 0) + 1);
      const n = [...alive.values()].filter((k) => k === kind).length;
      if (n > (peak.get(kind) ?? 0)) peak.set(kind, n);
    } else if (l.includes(":packet/name :remove-entities")) {
      const m = l.match(/:ids \[([0-9 ]*)\]/);
      if (m) for (const id of m[1].split(" ")) if (id) alive.delete(Number(id));
    }
  }
  for (const [kind, n] of added) {
    const t = total.get(kind) ?? { files: 0, added: 0, peak: 0 };
    t.files++;
    t.added += n;
    t.peak = Math.max(t.peak, peak.get(kind) ?? 0);
    total.set(kind, t);
  }
}

for (const f of process.argv.slice(2)) await one(f);
console.log(JSON.stringify(Object.fromEntries([...total].sort((a, b) => b[1].added - a[1].added)), null, 1));
