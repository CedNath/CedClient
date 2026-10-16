# FarmHelper port

The code in this package (`ced.cedclient.farmhelper`) is ported from **FarmHelper** by JellyLab and contributors:
https://github.com/JellyLabScripts/FarmHelper

FarmHelper is licensed under **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International**
(CC BY-NC-SA 4.0, https://creativecommons.org/licenses/by-nc-sa/4.0/). As required by that license, every file in this
package stays under CC BY-NC-SA 4.0 (not CedClient's LGPL), credits the original authors, and may not be used
commercially.

Changes from the original:
- updated from Forge / Minecraft 1.8.9 to Fabric / Minecraft 26.x (Mojang names, new packet and block APIs);
- trimmed to the crop macros, rotation and game-state handling, failsafe detection and a status HUD;
- failsafes stop the macro and alert the player instead of running scripted reactions (no automatic chat replies
  or recorded-movement playback);
- the settings live in CedClient's own module (FarmHelper, Cheats panel) instead of the original config GUI.
