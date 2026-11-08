# 26.3 port: what to check

Checked already (offline dev client, creative world CedTest): boots, all mixins apply, main menu, world join, F11 fullscreen, ClickGUI (windowed + fullscreen), category switching, module toggles, settings panel, sliders, tooltips.

Run offline: `./gradlew runClient -Pdevauth=0` (add `-PmixinAudit=1` to load every mixin target at startup and log failures).

## Keys and input (SDL scancodes)
- [ ] Old config loads: a bind saved on 26.1.2 (bare number) converts to the right key; new binds save as `{"sdl": n}`
- [ ] KeybindSetting: click, press a key, label shows the key name; Esc / Backspace / Delete unbind; right click unbinds
- [ ] Number-row and keypad 1-5 in Leap Menu
- [ ] Freecam (B), Zoom (C hold), FarmHelper toggle, Waypoints add key, Hiders peek key fire in the world
- [ ] Mouse buttons: left = 1, right = 3 now; check every `button()` comparison (ClickGUI right-click, popups, TerminalSolver drag click, Spotify seek bar, Cosmeticblocks tools)
- [ ] Text boxes: ctrl+C / V / A / X, arrows, home / end, enter / numpad enter (TextInputHandler)
- [ ] CursorGlide moves the real cursor (SDL_WarpMouseInWindow)

## World drawing (submit collector)
- [ ] BlockESP boxes / tracers / labels, EntityESP, Starred Mob Highlight (cheat + legit), Floordrops, Pest highlighter
- [ ] DoorESP, SecretHighlight, SimonSays lines, SunGecko, DungeonMap chests, Waypoints boxes + labels, PositionalMessages, VampireSlayer boxes + walk path, DojoHelper debug lines, BeachBall marker + name tag, CritterWorld
- [ ] Cosmetic blocks renderer (selection, ruler, replaced-block wireframe)
- [ ] Halo layer (spin / bob / pulse)
- [ ] Through-walls (LINES_ESP / FILLED_ESP) vs depth-tested variants; the old OutputTarget item-entity target is gone, check z-fighting and translucency order
- [ ] Name tags no longer take a max distance argument: check label visibility at range

## NanoVG / GUI
- [ ] All NVG HUDs and popups (ClickGUI popups, inventory buttons, tooltips via TooltipState, search overlay, price input)
- [ ] Two NVG draws in one frame do not overwrite each other (tooltip over ClickGUI)
- [ ] Text renders (font atlas, sampler state), not only fills
- [ ] Resize the window, change GUI scale, F11 back and forth
- [ ] Vulkan backend: NVG draws nothing (OpenGL only). Decide: force OpenGL or rewrite
- [ ] MouseLookDebugger keybind (cheat), unbound by default

## Mixins moved or changed
- [ ] Hud split: Crosshair, selected item name (ActionBarHud), vignette / portal / confusion overlays, scoreboard entry, keep chat on disconnect, action bar text (SlayerCheats)
- [ ] Render Optimizer: water / fire / wall overlays, weather (rain, snow, splash particles + sounds), block break particles + crack particles
- [ ] Cosmetic blocks: destroy and crack particles, hit sound (now in ClientLevel.playBreakingSound)
- [ ] Custom Sky: time of day override (new ClientClockInstance tagging), SkyBox cube (END_SKY pipeline), saturation post chain
- [ ] Vanilla Colors: shader include swap (ShaderManager.loadInclude), needs a Hypixel pack
- [ ] Tooltips: scrollable / custom / fit-to-screen (tooltip signature gained a boolean)
- [ ] ProtectItem drop key (MultiPlayerGameMode.dropItem), sign replacements (SearchOverlay, AuctionPriceInput), Clean View entity hiding
- [ ] RenderEvent fires once per frame (LevelRenderer.render TAIL)
- [ ] Sodium mixin (SodiumLevelSliceMixin) with Sodium installed (not present in dev)

## Behaviour that changed on purpose
- [ ] Borderless Fullscreen module removed: vanilla F11 is borderless on Windows, "Exclusive Fullscreen" is a video option
- [ ] `player.swing` now through `swingMainHand()` (Fishing, Coralot, TropicalFish, Tinydancer, LeftClickSecrets)
- [ ] Terminal Simulator / Solver, Dojo, BeaconSolver, AutoFusion colours (colour collections replaced the `*_STAINED_GLASS_PANE` etc constants)
- [ ] Farm helper tool check uses item tags (HOES / AXES)
- [ ] Update screen and cosmetic export open links / folders through `Blaze3D.openUri` / `openPath`

## Hypixel only (cannot test in singleplayer, build only)
- [ ] Everything SkyBlock: slayers, dungeons, Kuudra, terminals, party, chat features, Spotify

## New look (2026-11-08, docs/superpowers/specs/2026-11-08-premium-look-design.md)
Checked in singleplayer: menu (both accents), accent picker, HUD editor previews, one-time look reset.
- [ ] Blaze Slayer status card during a real boss: title, status line, boss count headline, mobs slain row
- [ ] Dungeon Splits during a real run: see-through, tight lines, halo readable over bright floors and lava
- [ ] Secret HUD, Room Times, Leap Counter, Tick Timers, Deployable / Ragnarock timers in a run: see-through with halo, no card
- [ ] Run Profit (profit headline, Runs / Per run rows), Slayer (name + boss timer, kills headline with /h), Daily Tasks (title + "n left", dotted list), Critter Safari (bold title + subtitle, divider, half-height gaps): Blaze card layout. Checked in the HUD editor: Run Profit, Critter cards; Slayer and Daily Tasks not seen (panels overlap in the dev layout)
- [ ] Time HUD, Chat Channel HUD, Client Notices, Entity ESP HUD: see-through halo text in the palette (card only with their Background on). Chat Channel lost its "Custom Font" toggle (Mod Settings > Font covers it). Client Notices no longer borrow Compact Scoreboard's panel
- [ ] Item rarity / Hypixel-coloured text inside HUDs keeps its game colours (only HUD-picked greys / greens / reds / golds are remapped)
- [ ] `[CC]` chat prefix follows the accent
- Left on their own look on purpose: Dungeon Map, stat bars (Health / Mana ...), item cooldown numbers on hotbar slots, Fun Visuals, Screenshot Preview
- Held Item Name stays vanilla-drawn (vanilla renders it; the panel only moves it). Music keeps its album-colour panel
- Follow-ups: standalone screens (search overlay, price input, command keybinds, cosmetic presets, button editor, update screen, debug panel) only got readability fixes, not the full panel restyle

## Build and release
- [ ] `./gradlew build` makes `-cheat` and `-legit` jars; legit jar has no cheat code
- [ ] `//#if CHEAT` blocks still balanced after the port (the legit build caught two)
- [ ] Unused `val poseStack = context.poseStack()` left in several ESP files, harmless, clean up
- [ ] Push `26.3`, send jar to the GC only after the in-game checks above
