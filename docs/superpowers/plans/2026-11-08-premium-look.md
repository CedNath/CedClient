# CedClient Premium Look Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give CedClient one look: a shared palette, a full HUD card for trackers, see-through compact HUDs with a soft halo, a restyled menu, one accent (default none) picked on first menu open, and fewer look options.

**Architecture:** A new `Palette` object holds every colour token. `HudCard` keeps being the one drawing path for HUDs: its card becomes a single style, its card-less text gets a NanoVG font-blur halo, and it gains small row/header building blocks. `Theme` loses the style dropdowns and gets one `accent` plus two hidden markers (`accentChosen`, `lookVersion`). The menu reads `Palette` + accent; a new `AccentPickerPopup` opens on first menu open.

**Tech Stack:** Kotlin, Fabric (Minecraft 26.3), LWJGL NanoVG (GL3) via `ced.cedclient.render.nvg.NVGRenderer`, Gson config.

**Spec:** `docs/superpowers/specs/2026-11-08-premium-look-design.md`

## Global Constraints

- Work only in `C:\Users\natha\IdeaProjects\CedClient-26.3` on branch `26.3`. No backports.
- The worktree has uncommitted user work (`CedClient.kt`, `ConfigManager.kt`, `HudCard.kt`, `DungeonAlertsBatch.kt`, `DungeonBlocksBatch.kt`, `LeapMenu.kt`, `WitherDragons.kt`, `WorldText.kt`, new `FireSaleBuyer.kt`). Never overwrite or revert it. Before the first commit, ask the user how to handle it (commit it first on its own, or leave it out of these commits).
- Commit only with the user's OK.
- Render-only. No chat, commands or packets. Nothing that spams.
- Both jars must build: `./gradlew build` produces `-cheat` and `-legit` jars. Keep `//#if CHEAT` / `//#else` / `//#endif` blocks intact and mirror changes into both sides (e.g. `HudElement.all`).
- Testing: no unit-test source set exists, and none is added. Verification is the build plus the offline dev client only: `./gradlew runClient -Pdevauth=0`, singleplayer world `CedTest`, HUD editor `/cc hud`. Never a real account. Send input only to the offline java process. Hypixel-only checks go to `TESTING-26.3.md`.
- Every new piece of logic logs through `ced.cedclient.utils.Debug.line(tag: String, msg: String)`.
- Palette values (copy exactly): text `#F2F3F5`, muted `#9A9CA5`, muted see-through `#B4B6BE`, faint `#6B6E78`, line white 7 %, edge white 9 %, surface `#0F1015` 80 %, panel `#0D0E12` 86 %, good `#4CC38A` / see-through `#5EDB9C`, bad `#E5484D` / see-through `#FF6B6F`, gold `#E9B949`.
- Swatches: None `#F2F3F5`, Red `#E5484D`, Amber `#F5A524`, Green `#4CC38A`, Cyan `#4CC9E0`, Blue `#6E8BFF`, Violet `#9B8CFF`, Pink `#F06FB0`.
- Radius 8 for cards and panels, 10 for the picker; borders 1 px. Compact line height 1.18× font size.

## Review Focus

1. **Accent None under white text**: with accent `#F2F3F5`, any label, knob or "RUN" text drawn on an accent fill becomes white on white. Expected: everything stays readable (`Palette.onAccent()`). Pinned in Task 4 Step 4.
2. **Minecraft font + halo**: `NVGRenderer.text` sends the Minecraft font through `minecraftText`, which ignores `nvgFontBlur`. Expected: card-less text still gets a soft dark surround. Pinned in Task 1 Step 6.
3. **Updating players' old config**: old `hud-styles.json`, `outline` entries in `hud-looks.json`, saved `GUI Accent` / `HUD Accent` / `HUD Style` / `Menu Style`. Expected: no errors, the look reset runs exactly once, the picker shows once, positions and other settings survive. Pinned in Task 3 Step 4.
4. **Picker closed without choosing**: the menu closed by a key other than Esc, or the game quit while the picker is open. Expected: nothing is saved and the picker shows again on next open. Pinned in Task 6 Step 5.
5. **HUD scale extremes**: halo blur at panel scale 0.5 and 3.0. Expected: no smear at small scale, still visible at large. Pinned in Task 1 Step 6.

---

### Task 0: Agree how to handle the uncommitted work

- [ ] **Step 1:** Run `git -C C:/Users/natha/IdeaProjects/CedClient-26.3 status --short` and show the user the list.
- [ ] **Step 2:** Ask the user: commit it first as its own commit, or keep it out of these commits (stage only this plan's files). Follow the answer for every later commit step.

### Task 1: Palette, halo text and the single card

**Files:**
- Create: `src/main/kotlin/ced/cedclient/ui/Palette.kt`
- Create: `src/main/resources/assets/cedclient/font/inter-semibold.ttf` (Inter SemiBold, SIL OFL, same licence as `inter.ttf`; ask the user before downloading it from the official Inter release)
- Modify: `src/main/kotlin/ced/cedclient/render/nvg/NVGRenderer.kt` (font list ~line 43, text functions ~422-460)
- Modify: `src/main/kotlin/ced/cedclient/features/impl/render/HudCard.kt` (`card`, `text`, `luminance`)
- Modify: `src/main/kotlin/ced/cedclient/features/impl/misc/Theme.kt` (`hudBorderRgba` only)

**Interfaces:**
- Produces: `object Palette` (package `ced.cedclient.ui`) with ARGB `Int` constants `TEXT, MUTED, MUTED_SEE, FAINT, LINE, EDGE, SURFACE, PANEL, GOOD, GOOD_SEE, BAD, BAD_SEE, GOLD, ON_ACCENT_DARK (0xFF111216)`, `val SWATCHES: List<Pair<String, Int>>` (order and values from Global Constraints), `fun luminance(rgb: Int): Double` (moved from `HudCard`), `fun onAccent(accentArgb: Int): Int` (= `ON_ACCENT_DARK` when luminance > 0.5, else `TEXT`).
- Produces: `NVGRenderer.boldFont: Font` and `NVGRenderer.textHalo(text: String, x: Float, y: Float, size: Float, alpha: Float, font: Font)`.
- Produces: `HudCard.LINE_HEIGHT = 1.18f`, `HudCard.pad(label: String, scale: Float): Float` (6·scale when that HUD's card is on, 1·scale when off).

- [ ] **Step 1: Create `Palette.kt`** with the constants above. Alpha bytes: LINE `0x12`, EDGE `0x17`, SURFACE `0xCC`, PANEL `0xDB`. Add a `init { check(onAccent(0xFFF2F3F5.toInt()) == ON_ACCENT_DARK); check(onAccent(0xFF6E8BFF.toInt()) == TEXT) }` self-check.
- [ ] **Step 2: Add `boldFont`** next to `defaultFont`, loading `cedclient:font/inter-semibold.ttf`. When the Minecraft font option is on, `boldFont` falls back the same way `defaultFont` does.
- [ ] **Step 3: Add `textHalo`.** NanoVG path: set font size/face like `text`, `nvgFontBlur(vg, size * 0.16f)`, fill black at `0.95f * alpha`, `nvgText`, then `nvgFontBlur(vg, 0f)`. Minecraft-font path: draw `minecraftText` in black at `0.35f * alpha` at the 8 one-glyph-pixel offsets.
- [ ] **Step 4: Rework `HudCard`.** `card()` draws only `rect(SURFACE)` + `hollowRect(EDGE, 1f * scale)` with radius `8f * scale` (drop the `HudStyle` `when` and `Theme.roundedHud`). In `text()`, replace both the outline loop and the drop shadow with one `NVGRenderer.textHalo(...)` when the card is off; pure black stays untouched. `luminance` / `linear` move to `Palette`. Add `LINE_HEIGHT` and `pad`. `Debug.line("HUD Look", "halo pass for <label>")` once per label per session.
- [ ] **Step 5: Point `Theme.hudBorderRgba` at `Palette.EDGE`** so the 13 files that outline panels with it pick up the faint border.
- [ ] **Step 6: Verify.** Run `./gradlew build`; expected `BUILD SUCCESSFUL` and both jars in `build/libs`. Then `./gradlew runClient -Pdevauth=0`, open CedTest, `/cc hud`, and take screenshots: (a) default font, (b) Font = Minecraft, (c) one HUD at scale 0.5 and 3.0, (d) looking at the sky. Expected: cards are dark with a faint border and no stripe, card-less text has a soft dark surround in all four.
- [ ] **Step 7: Commit** `feat(look): palette, halo HUD text, single HUD card`.

### Task 2: Theme cleanup and one accent

**Files:**
- Modify: `features/impl/misc/Theme.kt`
- Delete: `features/impl/render/HudStyle.kt`
- Modify: `features/impl/render/HudLook.kt` (drop outline read/write; ignore old `outline` keys)
- Modify: `features/impl/render/HudCard.kt` (`outlineToggle` removed)
- Modify: `features/impl/render/Masterhudeditscreen.kt:144-146` (remove "Style" and "Outline" buttons, keep "BG")
- Modify: `features/impl/funqol/FightTimersBatch.kt`, `features/impl/funqol/SkyblockExtras.kt` (remove their "Text Outline" settings)
- Modify: `ui/clickgui/ClickGUI.kt:493`, `utils/ChatPrefix.kt:31`

**Interfaces:**
- Produces: `Theme.accent: ColorSetting` ("Accent", default `Color(0xF2, 0xF3, 0xF5)`, section "Colors"), `Theme.accentChosen: BooleanSetting` ("Accent Chosen", false, hidden), `Theme.lookVersion: NumberSetting` ("Look Version", 0.0, 0.0..10.0, hidden).
- Keeps (now backed by `accent`): `Theme.hudAccentRgba`, `ClickGUI.clickGUIColor`.

- [ ] **Step 1:** Remove `guiAccent`, `hudAccent`, `hudStyle`, `roundedHud`, `menuStyle` from `Theme` and `addSettings`; add `accent`, `accentChosen`, `lookVersion`. `resetColors` sets `accent` back to its default and its description becomes "Back to no accent." `menuOpacity` loses its `menuStyle` dependency.
- [ ] **Step 2:** Delete `HudStyle.kt`, fix its 3 callers, remove the editor's Style and Outline buttons, and remove the "Text Outline" settings. `HudLook` keeps `background` only and skips `outline` keys when loading.
- [ ] **Step 3:** Make `ClickGUI.clickGUIColor` return `Theme.accent.value`, and make `hudAccentRgba` return `accent.value.rgba`.
- [ ] **Step 4: Verify.** `./gradlew build` passes, and `grep -rn "HudStyle\|menuStyle\|guiAccent\|roundedHud" src` returns nothing. In the dev client, Mod Settings shows one Accent and no style dropdowns, and the HUD editor toolbar shows only BG.
- [ ] **Step 5: Commit** `refactor(theme): one accent, drop style dropdowns and outline toggles`.

### Task 3: Compact HUDs card-less by default + one-time look reset

**Files:**
- Modify: `features/impl/render/HudCard.kt` (`attachBackgroundSettings`, new `FULL_CARD` set, `isBackgroundName` from private to internal)
- Create: `features/impl/render/LookMigration.kt`
- Modify: `CedClient.kt` (call after config load; user WIP is in this file, so edit only the one line)

**Interfaces:**
- Produces: `HudCard.FULL_CARD: Set<String>` = labels "Slayer" (Blaze status), Vampire Slayer's label, "Run Profit", Skyblock Stats' label, Daily Reset's label, the three Critter Safari labels, "Music", "FarmHelper". Read the exact strings from each object's `label`. Every other label in `HudElement.all` is compact; "Dungeon Map" is untouched.
- Produces: `object LookMigration { const val CURRENT = 1; fun run() }`.

- [ ] **Step 1:** For compact labels, default to card off: add them to `cardOffByDefault`, and set each module's own Background toggle default to false (`BooleanSetting` default only; saved values are handled by Step 2).
- [ ] **Step 2: `LookMigration.run()`.** If `Theme.lookVersion.value < CURRENT`: clear the `hud-looks.json` entries, `reset()` every module's own background BooleanSetting (names matched by `HudCard.isBackgroundName`), set `lookVersion = CURRENT`, then `ConfigManager.saveModulesOnly()`. `Debug.line("Look Migration", "reset N HUD backgrounds")`. Otherwise do nothing.
- [ ] **Step 3:** Call `LookMigration.run()` once after the module config loads in `CedClient.kt`.
- [ ] **Step 4: Verify with an old config.** Back up `run/config/cedclient`, then put in a copy holding a `hud-styles.json`, a `hud-looks.json` with `{"Secret HUD":{"background":true,"outline":true}}`, and old accent keys. Launch: no errors in the log, Secret HUD has no card, the debug line shows. Turn Secret HUD's BG on in the editor, relaunch: it stays on (the reset did not run again). Restore the backup.
- [ ] **Step 5: Commit** `feat(look): compact HUDs see-through by default, one-time look reset`.

### Task 4: Menu restyle — Classic layout and accent fills

**Files:**
- Modify: `ui/clickgui/Panel.kt:63-172`, `ui/clickgui/ModuleButton.kt:40-90`, `ui/clickgui/Popup.kt` (frame colours)
- Modify: every `clickGUIColor` site that draws text or a knob on an accent fill (69 hits in 27 files: `grep -rn clickGUIColor src`)

**Interfaces:**
- Consumes: `Palette.*`, `Palette.onAccent`, `NVGRenderer.boldFont`, `ClickGUI.clickGUIColor`.

- [ ] **Step 1: Panel.** Background `Palette.PANEL` with a 1 px `EDGE` border and radius 8 (the round bottom uses the panel colour, never the accent). Header: title uppercase, `boldFont`, centred, letter-spaced about 0.14 em (draw per character or use `nvgTextLetterSpacing`), then a 1 px `LINE` hairline. Drop the count and the accent bar. Section headings: `MUTED`, uppercase, `LINE` hairline. Scrollbar thumb: white at 20 %.
- [ ] **Step 2: ModuleButton (Classic).** No row fill. Enabled = `TEXT` + 5 px accent dot 12 px from the right edge. Disabled = `FAINT`. Hover = white at 4 %. `alwaysEnabled` modules show as enabled without the dot. Name stays centred.
- [ ] **Step 3: Popups** use the `PANEL` / `EDGE` / radius 8 frame.
- [ ] **Step 4: Accent-fill audit.** For each `clickGUIColor` hit that fills a shape with text or a knob on top (toggle knobs, "RUN" pills, selected dropdown items, buttons), draw that text or knob with `Palette.onAccent(accent)`. Verify in the dev client with the accent set to `#F2F3F5` and then `#6E8BFF`: open a module with a toggle, a slider, a dropdown, an action button and a search field. Expected: every label and knob is readable in both.
- [ ] **Step 5: Verify.** `./gradlew build` passes. Screenshot the Classic menu next to `visual-style.html`; expected: same structure.
- [ ] **Step 6: Commit** `feat(menu): restyle classic layout, readable on any accent`.

### Task 5: Menu restyle — Compact layout

**Files:**
- Modify: `ui/clickgui/CompactLayout.kt` (`tint` ~311, colour constants 326-333, the `menuStyle` branches ~138-145 and ~315-324), `ui/clickgui/ModuleButton.kt:150-180` (`drawCard`)

- [ ] **Step 1:** Remove the Glass / Tinted / Neon / Gradient / Minimal branches; `tint` keeps only Solid plus `menuOpacity`. Map `BG` to `PANEL`, `CARD` to white 4 %, `CARD_HOVER` to white 7 %, `EDGE` to `Palette.EDGE`, `MUTED` to `Palette.MUTED`, `PILL_OFF` to white 10 %.
- [ ] **Step 2:** `drawCard`: the extended marker becomes the accent dot instead of the 4 px bar, the off text is `FAINT`, and the pill/knob text uses `onAccent` when on.
- [ ] **Step 3: Verify.** Build passes. Set Menu Layout = Compact in the dev client and screenshot it with accent None and Cyan; expected: same rules as Classic and readable.
- [ ] **Step 4: Commit** `feat(menu): restyle compact layout`.

### Task 6: First-open accent picker

**Files:**
- Create: `ui/clickgui/AccentPickerPopup.kt`
- Modify: `ui/clickgui/ClickGUI.kt` (`init` ~328), `features/impl/misc/Theme.kt` (`pickAccent` action)

**Interfaces:**
- Consumes: `Popup(title, width, height)`, `ClickGUI.openPopup`, `Theme.accent`, `Theme.accentChosen`, `Palette.SWATCHES`.
- Produces: `class AccentPickerPopup : Popup("Pick your accent", 640f, 420f)`; `Theme.pickAccent: ActionSetting` ("Pick Accent", section "Colors", added to `addSettings`).

- [ ] **Step 1: Layout.** One line of muted text: "Used for headline numbers, the dot on enabled modules and the chat prefix. You can change it any time in Mod Settings." Then 8 round swatches (34 px, selected = 2 px `TEXT` ring) with names under them. Then the live preview: three module rows (two on with the dot), a big number `31` in the swatch colour with "bosses / hour", and a `[CC] Ready` line. Then the Skip (ghost) and Done (filled `TEXT`, dark label) buttons. Clicking a swatch only changes the preview.
- [ ] **Step 2: Choices.** Done: `accent = swatch`, `accentChosen = true`, save, close. Skip or Esc: `accent = default`, `accentChosen = true`, save, close. Debug lines: "shown", "picked <name>", "skipped".
- [ ] **Step 3: Trigger.** In `ClickGUI.init()`, if `!Theme.accentChosen.value`, call `openPopup(AccentPickerPopup())`. `Theme.pickAccent` opens it from Mod Settings regardless of the flag.
- [ ] **Step 4: Verify the first run.** Delete `accentChosen` from a test copy of the config, then launch and open the menu. Expected: the picker shows over the menu, Cyan + Done turns the dots, the headline numbers and `[CC]` cyan, and reopening the menu doesn't show the picker.
- [ ] **Step 5: Verify interrupted closes** (Review Focus 4). With `accentChosen` false, open the menu and close it with the menu key (not Esc), then reopen. Expected: the picker shows again. Quit the game with the picker open, relaunch: the picker shows again.
- [ ] **Step 6: Commit** `feat(menu): first-open accent picker`.

### Task 7: HUD building blocks + full-card HUDs

**Files:**
- Modify: `features/impl/render/HudCard.kt`
- Modify: the full-card HUDs: `BlazeSlayerMacro.kt` (StatusHud), `VampireSlayer.kt`, `RunProfit.kt`, `misc/SkyblockStats.kt`, `misc/Dailyreset.kt`, `CritterSafari.kt` (3 HUDs), `misc/Spotify.kt`, `FarmHelper.kt`

**Interfaces:**
- Produces in `HudCard`: `data class Row(val label: String, val value: String, val color: Int = Palette.TEXT)`;
  `fun headerHeight(size: Float): Float`, `fun header(title: String, subtitle: String?, x: Float, y: Float, width: Float, size: Float)`;
  `fun headlineHeight(size: Float): Float`, `fun headline(value: String, label: String, note: String?, x: Float, y: Float, width: Float, size: Float)` (value about 2× size, accent);
  `fun divider(x: Float, y: Float, width: Float, scale: Float)`;
  `fun rowsWidth(rows: List<Row>, size: Float): Float`, `fun rows(rows: List<Row>, x: Float, y: Float, width: Float, size: Float): Float` (label `MUTED` left, value right; returns height).
- Full-card padding: 12·scale vertical, 14·scale horizontal.

- [ ] **Step 1:** Add the building blocks. The title uses `boldFont`, the subtitle is `MUTED` with ` · ` separators, the headline note is `FAINT` and right-aligned.
- [ ] **Step 2:** Move each full-card HUD onto them, keeping what it shows. List-style HUDs (Daily Reset, Critter Safari, Music) use header + rows with no headline. Colours the HUD chose itself become palette values (good / bad / gold / text). Game-sourced text keeps its colours via `legibleComponent`. Module colour settings keep working; only their defaults change to palette values.
- [ ] **Step 3: Verify.** Build passes. In `/cc hud`, screenshot each full-card HUD preview. Expected: matches the full card in `visual-style.html` (header, optional headline, hairline, rows). Add the Hypixel-only checks ("Blaze status card during a real boss", "Run Profit after a run") to `TESTING-26.3.md`.
- [ ] **Step 4: Commit** `feat(hud): full-card building blocks and tracker HUDs`.

### Task 8: Compact HUDs — dungeons

**Files:** `DungeonSplits.kt` (incl. `CurrentSplit`), `SecretHud.kt`, `RoomTimes.kt`, `ScoreCalculator` file, `LeapCounter.kt`, `F4Features` file (CHEAT), `MaxorPanel` / `GoldorPanel` / `StormPyPanel` / `StormPadPanel` file(s), `WitherDragons.kt` (has user WIP: edit only render code), `BlessingDisplay`, `MaskTimers`, `SpringBoots` files (find with `grep -rln "object <Name>" src`).

**Interfaces:**
- Consumes: `HudCard.Row`, `HudCard.LINE_HEIGHT`, `HudCard.pad`, `HudCard.text`, `Palette.MUTED_SEE / TEXT / GOOD_SEE / BAD_SEE / GOLD`, `Theme.hudAccentRgba`.
- Produces in `HudCard` (first task to need them): `fun compactWidth(rows: List<Row>, size: Float): Float`, `fun compact(rows: List<Row>, x: Float, y: Float, size: Float): Float`. A row draws as `label value`: label in `MUTED_SEE`, one space, value in its colour; an empty label draws the value alone. Line step is `size * LINE_HEIGHT`. Returns the height.

- [ ] **Step 1:** Add `compactWidth` / `compact`.
- [ ] **Step 2:** Move each HUD above onto `compact`, with `pad(label, scale)`, keeping its content and settings. Its first line is `Title headline` where it has one (`Splits 4:12`); the headline value is in accent. Delta values use `GOOD_SEE` / `BAD_SEE`. `DungeonSplits` stops drawing outside `HudCard`.
- [ ] **Step 3: Verify.** Build passes. HUD editor screenshot of each preview over a bright and a dark spot. Expected: matches option C in `compact-v3.html`: no box, tight lines, halo. Add "Splits/Secrets during a real run" to `TESTING-26.3.md`.
- [ ] **Step 4: Commit** `feat(hud): compact dungeon HUDs`.

### Task 9: Compact HUDs — everything else

**Files:** `Timehud.kt`, `EventCountdown.kt`, `ArmorHud.kt`, `Chatchannelhud.kt`, `misc/ChatPing.kt`, `ActionBarHud.kt` (Notices, ItemName), `DeployableTimers.kt`, `RagnarockTimer.kt`, `SunGecko.kt`, `PingFpsHud`, `SbKickTimer`, `PetDisplay`, the Health / Mana / Vitality / Overflow / Defence panels and `SkillDisplay` (misc), `WarpCooldown`, `EntityESPHud.kt` (friends only), `ClientTitles` panels, `Itemcooldowns.kt`.

**Interfaces:** Consumes the same as Task 8.

- [ ] **Step 1:** Move each onto `compact` the same way as Task 8. HUDs that are mostly icons (Armor HUD, Pet Display) keep their icons and only take the palette for their text. `Itemcooldowns` stops drawing outside `HudCard`.
- [ ] **Step 2: Verify.** Build passes. HUD editor screenshots of all of them. Expected: one consistent see-through look.
- [ ] **Step 3: Commit** `feat(hud): compact timers, stats and info HUDs`.

### Task 10: Leftover self-drawing screens

**Files:** `misc/SkyblockStats.kt` (if not finished in Task 7), `render/FunVisuals.kt`, `misc/UtilityBatch.kt`, `misc/FreezeCapture.kt`, `misc/ScreenshotPreview.kt`

- [ ] **Step 1:** For each file, decide whether it is a HUD panel (then move it onto `HudCard`) or a screen or preview (then swap its hard-coded colours for `Palette` tokens where the change stays local). List any file left as is, with the reason, at the end of `TESTING-26.3.md`.
- [ ] **Step 2: Verify.** Build passes. `grep -rhoE "0x[0-9A-Fa-f]{8}" <HUD files from Tasks 7-9> | sort -u | wc -l` is far below the 79 colours counted at design time, and every remaining value is game text or black.
- [ ] **Step 3: Commit** `chore(look): palette for remaining self-drawn panels`.

### Task 11: Final check

- [ ] **Step 1:** `./gradlew build`; both jars present.
- [ ] **Step 2:** In the dev client, with the HUD editor open, compare FPS (F3) against a jar built from the pre-plan commit, with the same HUDs on. Expected: within a few FPS. If not, drop the halo's second pass first.
- [ ] **Step 3:** Run through the Review Focus list once more on the final build.
- [ ] **Step 4:** Make sure `TESTING-26.3.md` holds every Hypixel-only check from Tasks 7-9.
- [ ] **Step 5:** Report to the user with screenshots of the menu, a full card and the compact HUDs. Don't push or send a jar until the user says so.
