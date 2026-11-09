// Turns a played-in CedClient config into the defaults bundled in the jar.
//   node scripts/make-defaults.mjs <path to .../config/cedclient>
// Writes src/main/resources/cedclient_defaults.json (also in the Legit jar) and cedclient_defaults_cheat.json
// (left out of the Legit jar by build.gradle.kts). Needs a build first: it asks build/preprocessed/legit which
// names exist in the Legit edition. Not copied on purpose: on / off state, keybinds, personal history and file names,
// and everything of the friends-only modules.
import fs from "node:fs";
import path from "node:path";

const src = process.argv[2];
if (!src) throw new Error("usage: node scripts/make-defaults.mjs <config/cedclient folder>");
const root = path.resolve(import.meta.dirname, "..");

const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]));
const legitText = walk(path.join(root, "build/preprocessed/legit")).map((f) => fs.readFileSync(f, "utf8")).join("\n");
const inLegit = (s) => legitText.includes(JSON.stringify(s));
// A module exists in the Legit edition when its class is declared there: ("Name", ...).
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const legitModule = (name) => new RegExp("\\b(?!(?:listOf|mapOf|setOf|arrayOf|to)\\()\\w+\\(\\s*" + esc(JSON.stringify(name)) + "\\s*,").test(legitText);

const FRIEND_MODULES = new Set(["PangolinCatcher", "CoralotHelper", "TropicalFishHelper", "Slayer Helper", "Blaze Slayer Macro", "FarmHelper",
  "Dojo Helper", "Beach Ball Helper", "Fire Sale Buyer", "Player ESP", "Mob ESP", "Item ESP", "Entity ESP"]);
const FRIEND_HUD_KEYS = new Set(["farmhelper", "blaze_macro", "entity_esp"]);
const FRIEND_HUD_NAMES = new Set(["Blaze Slayer Macro", "Entity ESP", "FarmHelper", "CoralotHelper Alert", "Beach Ball Helper Alert", "PangolinCatcher Alert"]);
// Things that point at the player's own files or history.
const PERSONAL = new Set(["SearchOverlay|Bazaar History", "SearchOverlay|Auction History", "Custom Cape|Image", "Custom Sky|Skybox",
  // Accent stays white until the player picks one, and Accent Chosen stays false so the first-open picker shows.
  "Mod Settings|Accent", "Mod Settings|Accent Chosen", "Mod Settings|Look Version"]);

const cfg = JSON.parse(fs.readFileSync(path.join(src, "config.json"), "utf8"));
const huds = JSON.parse(fs.readFileSync(path.join(src, "hud/huds.json"), "utf8"));
const splits = JSON.parse(fs.readFileSync(path.join(src, "data/dungeon_splits.json"), "utf8"));

const main = { modules: {}, huds: {} };
const cheat = { modules: {}, huds: {} };

for (const [name, data] of Object.entries(cfg.modules)) {
  if (FRIEND_MODULES.has(name)) continue;
  const settings = {};
  for (const [k, v] of Object.entries(data.settings ?? {})) {
    if (v && typeof v === "object" && "sdl" in v) continue; // keybind
    if (PERSONAL.has(`${name}|${k}`)) continue;
    settings[k] = v;
  }
  if (Object.keys(settings).length) (legitModule(name) ? main : cheat).modules[name] = settings;
}

// A panel label is a HUD's name, "<module> Alert" for a title.
const cheatModules = new Set(Object.keys(cfg.modules).filter((n) => !legitModule(n)));
const panelSide = (label) => {
  if (FRIEND_HUD_NAMES.has(label)) return null;
  const base = label.replace(/ Alert$/, "");
  if (cheatModules.has(base) || cheatModules.has(label)) return cheat;
  return inLegit(base) || inLegit(label) ? main : cheat;
};
// A HUD key belongs to the Cheat file when it is a cheat module's name squashed ("title_lividsolver", "blood_camp") or F4's bear.
const squash = (s) => s.toLowerCase().replace(/^title_/, "").replace(/[^a-z0-9]/g, "");
const cheatKeys = new Set([...cheatModules].map(squash).concat("spiritbear"));
const keySide = (key) => (cheatKeys.has(squash(key)) ? cheat : main);
for (const [key, value] of Object.entries(huds)) {
  if (["layout", "styles", "looks"].includes(key) || FRIEND_HUD_KEYS.has(key)) continue;
  keySide(key).huds[key] = value;
}
for (const side of [main, cheat]) {
  side.huds.layout = { w: huds.layout.w, h: huds.layout.h, panels: {} };
  side.huds.styles = {};
  side.huds.looks = {};
}
// A panel's scale is not in the layout: take it from the per-HUD record saved at the same spot, or from this list
// (HUDs whose record is not x / y / scale, or that share a record).
const SCALES = { "Client Notices": huds.action_bar?.notices?.scale, "Held Item Name": huds.action_bar?.itemName?.scale,
  "Critter Progress": huds.critter?.progress?.[2], "Critter Missing": huds.critter?.missing?.[2], "Critter Trades": huds.critter?.trades?.[2],
  "Dungeon Splits": splits.scale, "Current Split": splits.cscale };
const records = Object.values(huds).filter((v) => v && typeof v.x === "number" && typeof v.scale === "number");
for (const [label, p] of Object.entries(huds.layout.panels)) {
  const side = panelSide(label);
  if (!side) continue;
  const scale = SCALES[label] ?? records.find((r) => r.x === p[0] && r.y === p[1])?.scale;
  side.huds.layout.panels[label] = scale === undefined ? p : [...p.slice(0, 4), Math.round(scale * 100) / 100];
}
for (const key of ["styles", "looks"]) {
  for (const [label, v] of Object.entries(huds[key] ?? {})) {
    const side = panelSide(label);
    if (side) side.huds[key][label] = v;
  }
}

const out = (file, obj) => fs.writeFileSync(path.join(root, "src/main/resources", file), JSON.stringify(obj, null, 1) + "\n");
out("cedclient_defaults.json", main);
out("cedclient_defaults_cheat.json", cheat);
const count = (o) => `${Object.keys(o.modules).length} modules, ${Object.keys(o.huds).length} hud keys, ${Object.keys(o.huds.layout.panels).length} panels`;
console.log("main :", count(main));
console.log("cheat:", count(cheat), "->", Object.keys(cheat.modules).join(", "));
console.log("cheat panels:", Object.keys(cheat.huds.layout.panels).join(", "));
console.log("cheat hud keys:", Object.keys(cheat.huds).join(", "));
