// build.ts <steve-checkout> <node_modules-dir>: vendor steve's renderer (typecraft's viewer, the one
// steve's dashboard draws with) into resources/clojurecraft/viewer/web/ for clojurecraft.viewer.
//
//   viewer.js  the renderer's functions the page calls (entry below), without Babylon, which the
//              page loads from a CDN as the global BABYLON
//   worker.js  the chunk mesher's web worker (web/clientWorker.ts), unchanged
//
// The node_modules dir must hold esbuild 0.27 and buffer 6 (pnpm add esbuild@0.27.7 buffer@6.0.3 in
// a scratch directory). Run from the repository root:
//   node dev/viewer/build.ts ~/Developer/mc/upstream/steve <scratch>/node_modules
import { createRequire } from "node:module";
import { resolve } from "node:path";

const [steve, modules] = process.argv.slice(2);
const { build } = createRequire(resolve(modules, "x.js"))("esbuild");
const typecraft = resolve(steve, "src/lib/typecraft");
const out = resolve(import.meta.dirname, "../../resources/clojurecraft/viewer/web");

const entry = `
export { createViewer, setViewerAssets, addViewerColumn, removeViewerColumn, addViewerEntity,
  updateViewerEntity, removeViewerEntity, setViewerCamera, setViewerTime, resizeViewer, renderViewer }
  from "./viewer/viewer.ts";
export { createTextureAtlas, prepareBlockStates } from "./viewer/assets.ts";
export { setEntityModels } from "./viewer/entityRenderer.ts";
export { createChunkColumn, loadChunkColumn, GLOBAL_BITS_PER_BIOME, GLOBAL_BITS_PER_BLOCK }
  from "./chunk/index.ts";
export { vec3 } from "./vec3/index.ts";
export const fromBase64 = (s) => Buffer.from(s, "base64");
`;

const babylonGlobal = {
	name: "babylon-global",
	setup(b: any) {
		b.onResolve({ filter: /^@babylonjs\/core$/ }, () => ({ path: "babylon", namespace: "babylon-global" }));
		b.onLoad({ filter: /.*/, namespace: "babylon-global" }, () => ({
			contents: "module.exports = globalThis.BABYLON;",
			loader: "js",
		}));
	},
};

const shared = {
	bundle: true,
	platform: "browser" as const,
	format: "esm" as const,
	minify: true,
	inject: [resolve(steve, "scripts/buffer-shim.ts")],
	nodePaths: [modules],
	define: { "process.env.NODE_ENV": '"production"' },
	plugins: [babylonGlobal],
	banner: { js: "// vendored from steve's typecraft viewer by clojurecraft dev/viewer/build.ts; do not edit" },
};

await build({
	...shared,
	stdin: { contents: entry, resolveDir: typecraft, loader: "ts" },
	outfile: resolve(out, "viewer.js"),
});
await build({ ...shared, entryPoints: [resolve(typecraft, "web/clientWorker.ts")], outfile: resolve(out, "worker.js") });
console.log(`wrote ${out}/{viewer,worker}.js`);
