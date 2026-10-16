// Builds src/main/resources/assets/cedclient/vanilla_looks.json for Vanilla Textures.
// The live NEU repo stores most SkyBlock items as plain paper since its July 2026 "Resourcepack ->" rewrite, so the old looks
// come from the last commit before it instead:
//   gh api repos/NotEnoughUpdates/NotEnoughUpdates-REPO/tarball/0046933f3de14e6332f69de1d05a51fdb6888279 > repo.tgz
//   tar xzf repo.tgz --wildcards '*/items/*'
//   node scripts/vanilla_looks.mjs <extracted>/items
// Output: { "ITEM_ID": "legacy_item_id:damage[:skin texture hash]" }, "=modern_item_id:0[...]" when the entry already had a
// vanilla item_model (drills: diamond_pickaxe drawn as prismarine_shard). Entries already on Hypixel's own models are left
// out, so those items keep Hypixel's texture.
import fs from "node:fs";
import path from "node:path";

const dir = process.argv[2];
if (!dir) throw new Error("usage: node scripts/vanilla_looks.mjs <NEU repo items dir>");
const VALUE = /Value:\\?"([A-Za-z0-9+/=]+)\\?"/;
const MODEL = /ItemModel:\\?"([a-z0-9_.-]+):([a-z0-9_/.-]+)\\?"/;
const SKIN = /textures\.minecraft\.net\/texture\/([0-9a-f]+)/;

const out = {};
for (const f of fs.readdirSync(dir).sort()) {
    if (!f.endsWith(".json")) continue;
    const o = JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"));
    if (!o.internalname || !o.itemid) continue;
    const model = o.nbttag?.match(MODEL);
    if (model && model[1] !== "minecraft") continue;
    let look = model ? `=${model[2]}:0` : `${o.itemid.replace(/^minecraft:/, "")}:${o.damage ?? 0}`;
    const value = (o.itemid.endsWith("skull") || model?.[2] === "player_head") && o.nbttag?.match(VALUE)?.[1];
    const hash = value && Buffer.from(value, "base64").toString("utf8").match(SKIN)?.[1];
    if (hash) look += `:${hash}`;
    out[o.internalname.replace(/:/g, "-")] = look;
}
const target = new URL("../src/main/resources/assets/cedclient/vanilla_looks.json", import.meta.url);
fs.writeFileSync(target, JSON.stringify(out));
console.log(`${Object.keys(out).length} items`);
