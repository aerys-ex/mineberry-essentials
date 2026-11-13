# MineBerry - Essentials

A modern MineBerry control center for quick chat controls, safety tools, evidence reports, and Overworld/Nether coordinate conversion.

Open the refreshed **Control Center** with Right Control (rebindable under Options > Controls > Key Binds). It keeps every essential in one clean dashboard: module switches, keybind reminders, coordinate conversion, and evidence export.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This project is licensed under the MIT License.

## Abuse alert

After you accept a /tpahere, if the server announces that PvP mode was entered, a red box appears
top-left: **POSSIBLE TP KILL / RG ABUSE DETECTED**. Press `-` to confirm (screenshot + report zip
immediately) or `=` to skip. Both keys are rebindable under Options > Controls > Key Binds.
The panel opens with Right Control by default. The chat line the alert listens for is the
`pvpModePattern` regex in `config/mineberry_essentials.json`.

## Evidence reports

Testimony screenshots (cannot-PvP, kill, confirmed) and `log.txt` are saved in
`.minecraft/screenshots/testimony/`; tpahere screenshots go in `screenshots/tpaheres/`.


When the Evidence Recorder captures a kill after a /tpahere, it bundles the incident into
`.minecraft/screenshots/reports/<date>_<player>.zip` containing the screenshots, `evidence-log.txt`
and `HOW_TO_REPORT.txt` (ticket instructions). The panel's **Export** button rebuilds the zip for the
latest incident at any time.

## Chat modes (Clan, Admin, Party)

Three toggleable chat modules. Each one switches your normal chat to a command, and a badge shows
top-right while it is on. Commands you type yourself (starting with `/`) are never touched.
Only one chat mode can be active at a time (turning one on turns the others off).

| Module | Key (rebindable) | Sends as | Badge | Config setting |
| --- | --- | --- | --- | --- |
| Clan Chat | `J` | `/cc <message>` | green | `clanCommand` |
| Admin Chat | `K` | `/ac <message>` | red | `adminChatCommand` |
| Party Chat | `X` | `/pc <message>` | blue | `partyCommand` |

Party chat is meant for mini games. Change any command in `config/mineberry_essentials.json`.

## WorldEdit CUI

The full [WorldEditCUI](https://github.com/EngineHub/WorldEditCUI) source (tag `26.2+03`, EPL-2.0, see
`WORLDEDITCUI_LICENSE_EPL-2.0`) is built directly into this mod under `org/enginehub/worldeditcui`, so no separate
WorldEditCUI mod is needed (and don't install one alongside - the ids would clash). Its CUI protocol library is
bundled as jar-in-jar. The panel's **WorldEdit CUI** switch shows/hides the selection; the **Settings** button on that
row opens the WorldEditCUI config (colours, etc.). Its key binds are under Options > Controls > WorldEditCUI
(unbound by default). The optional Iris and ViaFabricPlus hooks were left out.

## Waypoints

A toggleable **Waypoints** module. Waypoints are saved in `config/mineberry_essentials.json` and drawn as
outlined boxes with a tall beam (also through walls). A small HUD list in the top-right shows an arrow and the
distance to the nearest ones (hidden together with the rest of the HUD using `Y`).

- `B` adds a waypoint at your feet, `N` opens the waypoint list (both rebindable).
- The panel's **List** button on the Waypoints row opens the same list: add by name and X/Y/Z,
  show/hide, delete.
- Waypoints belong to a dimension and only show in that dimension.
