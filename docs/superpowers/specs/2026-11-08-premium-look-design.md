# CedClient premium look — design

Date: 2026-11-08 · Branch: `26.3` (worktree `CedClient-26.3`)

## Intent

CedClient doesn't read as one product. The ClickGUI and the HUDs each have their own look, the default HUD style
("Rail": 40 % see-through box + red stripe) is disliked, and there are so many look options (5 HUD styles, 6 menu
styles, two accents) that the client has no identity. The reference the user likes is Ashfall (paid, closed source):
it feels expensive because it follows a few rules everywhere — one surface, very little colour, the same layout in
every panel — "not too much and not too little". We take those cues; we do not copy Ashfall's look.

Second rule from the user: HUDs that are on screen during dungeon runs and fights must take as little space as
possible — no background, see-through, tight lines.

## Goals

1. One visual language across the ClickGUI, its popups and every HUD.
2. Two HUD sizes with fixed rules:
   - **Full card** for trackers you glance at between fights (session stats, profit, checklists).
   - **Compact** for anything on screen during a run or fight: text only, no background, tight line spacing,
     readable on any scene through a soft halo.
3. Neutral palette. Colour only carries meaning (faster/slower, coins). One accent colour, default **none**.
4. A one-time accent picker the first time the menu opens.
5. Fewer look options: the client has one look, not a style menu.

## Non-goals

- Compact Scoreboard / Compact Tab restyle (later, separate).
- Open / popup animations (left as they are).
- Dungeon Map drawing (left as it is).
- Changing what any HUD shows. This is layout and styling only.
- Backports to 26.1.2 / 26.2.
- Mod icon (separate, deferred).

## Anti-goals (a failure even if it works)

- Anything that sends chat, commands or packets. This is render-only; Hypixel ban risk must stay zero.
- Rainbow, gradients, glows, neon, heavy shadows.
- Bringing per-HUD style pickers back in another form.
- A compact HUD that takes more screen space than it does today.
- Text that disappears on bright scenes (snow, lava, sky).

## Palette (one place in code)

Put these in one object the menu and HUD code both read (exact home is the builder's call). Values are sRGB.

| Token | Value | Use |
|---|---|---|
| text | `#F2F3F5` | values, titles, enabled modules |
| muted | `#9A9CA5` | labels and subtitles on cards |
| muted (see-through) | `#B4B6BE` | labels in compact HUDs (lighter, sits on the game) |
| faint | `#6B6E78` | disabled modules, side notes |
| line | white @ 7 % | hairline dividers |
| edge | white @ 9 % | 1 px border on cards, panels, popups |
| surface | `#0F1015` @ 80 % | full HUD card |
| panel | `#0D0E12` @ 86 % | menu panels and popups |
| good | `#4CC38A` (`#5EDB9C` see-through) | faster than PB, gains |
| bad | `#E5484D` (`#FF6B6F` see-through) | slower than PB, losses |
| gold | `#E9B949` | coins / profit |
| accent | user's pick, default none = `#F2F3F5` | see "Where the accent shows" |

Accent swatches: None `#F2F3F5`, Red `#E5484D`, Amber `#F5A524`, Green `#4CC38A`, Cyan `#4CC9E0`, Blue `#6E8BFF`,
Violet `#9B8CFF`, Pink `#F06FB0`.

Corner radius: 8 for cards and menu panels, 10 for the picker. Borders 1 px. Font stays Inter by default (the
existing Inter / Minecraft font option is kept).

Colours the HUD code picks itself come from this palette, not Minecraft's `§` colours (today's HUD files use 79
different hex values, including 7 greys and the bright `§a §6 §c §b`). Text that comes from the game (item names,
rarities, Hypixel lines) keeps its own colours and still goes through `HudCard.legible`.

## Full card

Layout, top to bottom:

1. **Header**: optional small square icon (accent at ~22 % fill, ~45 % border), title (semibold), subtitle in muted
   with `·` separators (e.g. `Inferno Demonlord IV · Spawning`).
2. **Headline**: one big number (~2× body size, accent colour), a muted label beside it (`bosses / hour`), an
   optional faint note right-aligned (`12 done`, `PB 3:58`).
3. Hairline.
4. **Rows**: label left in muted, value right in text colour; values may use good / bad / gold.
5. Optional item rows (`Blaze Rod x324 ... 3.84K`) in the same row style.

The headline is optional: list-style HUDs (Daily Reset, Critter Safari, Music) use header + rows only.

Surface: `surface` fill, 1 px `edge` border, radius 8. Padding roughly 12 px vertical / 14 px horizontal at scale 1
in the mockup; the builder maps this to GUI units next to today's `HudCard` sizes.

**Full-card HUDs**: Blaze Slayer status (`BlazeSlayerMacro.StatusHud`), Vampire Slayer, Run Profit, Skyblock Stats,
Daily Reset, Critter Safari (Progress / Missing / Trades), Music (Spotify), FarmHelper (friends only).

## Compact HUD

- **No background by default.** Text only; the game shows through.
- **Tight lines**: line height about 1.18× the font size, no extra padding between rows.
- **Text**: optional bold title with its headline value on the first line (`Splits 4:12`), then `label value`
  rows. Labels in muted (see-through), values in text colour, the headline value in accent. good / bad / gold
  where they mean something (`Maxor 3:22 +9s` in bad).
- **Soft halo** (chosen by the user over drop shadow and hard outline): before drawing a string, draw it in black
  with `nvgFontBlur` (about 2 px at scale 1, around 95 % alpha; a second wider, lighter pass if needed), then draw
  the crisp text on top. This replaces both of today's options for card-less text: the drop shadow in
  `HudCard.text` and the per-HUD Text Outline. `nvgFontBlur` is in LWJGL's NanoVG, which `NVGRenderer` already
  imports; it needs a small wrapper there. Pure-black text drawn on purpose stays as it is today.
- A user can still switch a compact HUD's background on in the HUD editor ("BG"). It then sits in the full-card
  surface, without a header or headline.

**Compact HUDs**: everything else in `HudElement.all` — Time HUD, Event Countdown, Armor HUD, Chat Channel HUD,
Dungeon Splits and Current Split, Chat Alerts, Action Bar notices and item name, F4 Features, Deployable Timers,
Ragnarock Timer, Sun Gecko, Score Calculator, Room Times, Secret HUD, Leap Counter, Ping/FPS, SB Kick Timer, Pet
Display, the Health / Mana / Vitality / Overflow / Defence panels, Skill Display, Mask Timers, Blessing Display,
Warp Cooldown, the Maxor / Goldor / Storm panels, Spring Boots, Wither Dragons, Entity ESP HUD (friends only), and
the Client Titles panels. Dungeon Map keeps its own drawing.

## Menu (ClickGUI)

Both layouts (Classic and Compact) stay and get the same treatment:

- **Panels and popups**: `panel` fill, 1 px `edge` border, radius 8.
- **Panel header**: uppercase, letter-spaced (~0.14 em), semibold, centred, hairline underneath.
- **Module rows**: name centred. Enabled = text colour, medium weight, plus a 5 px accent dot at the right.
  Disabled = faint. Hover = white wash at ~4 %. No filled accent rows.

### Where the accent shows

Only on: the enabled-module dot, slider fill, toggle "on" state, the focused input outline, the selected dropdown
item, the full card's headline number and icon, compact HUD headline values, and the `[CC]` chat prefix (already
follows the HUD accent in `ChatPrefix.msg`). With None every one of these is white (`#F2F3F5`). Everything else is
black, white and grey.

## Accent picker (first menu open)

- Shows when the ClickGUI opens and a hidden setting (e.g. `accentChosen`, saved with the module config) is false.
  Players updating from an older CedClient don't have it, so they also see it once.
- Modal over the dimmed, blurred menu: title "Pick your accent", one line ("Used for headline numbers, the dot on
  enabled modules and the chat prefix. You can change it any time in Mod Settings."), the 8 swatches with names
  under them, a live preview (three module rows, a big number, a `[CC]` chat line), buttons **Skip** and **Done**.
- Skip (and Esc) → accent None, `accentChosen` = true. Done → saves the swatch, `accentChosen` = true.
- Mod Settings keeps a single **Accent** colour setting and gets a **Pick Accent** action that opens the picker again.

## Settings cleanup (Mod Settings / `Theme`)

| Setting | Change |
|---|---|
| GUI Accent + HUD Accent | merged into one **Accent**, default None (`#F2F3F5`) |
| Reset Colors | resets to None (its text still says "default purple" — fix it) |
| HUD Style (5 styles), `HudStyle.kt`, `hud-styles.json`, editor "Style" button | removed |
| Menu Style (6 styles) | removed; the new look is the only one |
| Rounded HUD Corners | removed (always rounded) |
| Per-HUD Text Outline (editor "Outline" button, modules' own Text Outline settings) | removed; the halo replaces it |
| Fixed HUD Size, Font, Menu Layout, Menu Size, Menu Opacity, Menu Blur + strength, animations, resets, editor "BG" toggle, per-HUD Background Opacity | kept |

Old config values for removed settings are ignored without errors (`hud-styles.json`, `outline` entries in
`hud-looks.json`, old accent keys). Nobody's HUD positions or module settings are lost.

**One-time look reset on update.** Most players never touched their HUD backgrounds, so their saved values are just
the old defaults and would keep cards on compact HUDs. On the first load of the new version (a hidden "look version"
marker, saved with the config), clear `hud-looks.json` and set every module's own Background toggle back to its new
default. Positions, scales, Background Opacity and every other setting stay as they are.

## HUDs that draw themselves

These skip `HudCard` today: `DungeonSplits`, `SkyblockStats`, `Itemcooldowns`, `FunVisuals`, `UtilityBatch`,
`FreezeCapture`, `ScreenshotPreview`. Any that is a HUD panel moves onto the shared code so it follows the rules
above. Ones that are screens or previews rather than HUDs (likely `ScreenshotPreview`, `FreezeCapture`) only take the
palette and surface where that is simple; otherwise they stay as they are and the builder lists them.

## Constraints

- Work only on branch `26.3` in `C:\Users\natha\IdeaProjects\CedClient-26.3`. That worktree has uncommitted work
  (`CedClient.kt`, `ConfigManager.kt`, `HudCard.kt`, several dungeon files, new `FireSaleBuyer.kt`). Don't overwrite
  it; ask the user how to handle it before the first commit.
- Both jars must build (`gradle build` makes `-cheat` and `-legit`); keep the `//#if CHEAT` markers intact.
- Render-only. No chat, commands or packets. Nothing that spams.
- NanoVG draws on the OpenGL backend only (Vulkan draws no NanoVG); nothing here changes that.
- Testing: build, then the offline dev client only (`runClient -Pdevauth=0`) into the singleplayer world CedTest,
  never a real account. The HUD editor (`/cc hud`) previews every HUD there. Things that need Hypixel to show real
  data go into `run/TESTING-26.3.md` for the user to check.
- Add debug lines for new logic (picker shown / skipped / picked, halo pass), as with every feature.
- Performance: the halo adds one or two blurred text draws per string. Compare FPS in CedTest with the HUD editor
  open before and after.

## Left to the builder

- The exact GUI-unit sizes, paddings and font sizes (mockup values are CSS pixels at scale 1).
- The shape of the shared building blocks (header, headline, row, compact line, halo text).
- The order HUDs are moved over, and how the work is split into batches.
- Tuning the halo blur and alpha in game until it matches the mockup.

## Calls made without the user (check these)

- The exact palette hex values and the 8 swatch colours.
- Which HUD is full and which is compact (the user approved the rule and the rough split; the full list above is mine).
- Removing Rounded HUD Corners and the per-HUD Outline toggle (follow from "one look" and the halo choice).
- Keeping the editor's "BG" toggle so a compact HUD can still get a card.
- Merging GUI Accent and HUD Accent into one Accent, and adding a Pick Accent action to reopen the picker.
- Esc on the picker counts as Skip.
- The full card's headline is optional for list-style HUDs.
- The one-time look reset on update (clears saved HUD background / outline choices, keeps positions and settings).

## Mockups

Browser mockups from the brainstorm (scratchpad, not in the repo):
`visual-style.html` (full card, menu, accents), `compact-and-picker.html` (accent picker), `compact-v3.html`
(compact HUD with halo — option C) under
`C:\Users\natha\AppData\Local\Temp\claude\801943df69c68d0d\9b4bcf6f-927e-412f-89cf-0ab3e4aadaa9\scratchpad\.superpowers\brainstorm\17801-1794143048\content\`.
