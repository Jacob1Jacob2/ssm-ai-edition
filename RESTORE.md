# Restoring SSM AI Edition from this backup

This repository holds everything that is unique to the server: the plugin source, the built plugin,
the maps, the lobbies, the server configuration and the hosting scripts. It leaves out things that
are private (player lists, logs, bans, secrets) and things that can be downloaded again.

## What is here

| Folder | What it is |
|---|---|
| `Github code/ssm-brawl/plugin` | The game plugin (Kotlin). `gradlew build` makes the jar. |
| `test-server` | The Paper server folder: config, lobbies (`world`), game maps (`worlds`), plugin data. |
| `Maps`, `The lobby`, `winning map` | The original map, lobby and podium worlds. |
| `public-hosting` | Scripts for hosting it publicly (bundle builder, installer, tunnel, Oracle retry). |
| `assets` | Server icon. |

## Putting it back on a new PC

1. Install Java 25 (Eclipse Temurin).
2. Clone this repository.
3. Download into `test-server`:
   - Paper 26.3 build 245 (`paper-26.3-245.jar`) from papermc.io
   - into `test-server/plugins`: LibsDisguises 26.10.24 and PacketEvents 2.14.0 (spigot build)
4. Copy `test-server/server.properties.example` to `test-server/server.properties`.
5. Start it with `test-server/start-desktop.ps1`. Paper downloads its own libraries on first start.

The built plugin jar is already in `test-server/plugins`. To rebuild it from source, run
`gradlew.bat build` in `Github code/ssm-brawl/plugin` with `JAVA_HOME` pointing at JDK 25 and copy
`build/libs/super-smash-mobs-brawl-0.1.0-ai-all.jar` over it.

## How the backup is kept

`backup-to-github.ps1 "what changed"` saves everything that changed and pushes it.

The lobbies (`test-server/world`) are the exception. The server writes those files again every time
it runs, with nothing in them changed but times, so they are saved only when the lobby was really
changed: the plugin notes every use of build mode (`/chatgptbuild`) in
`test-server/plugins/super-smash-mobs-brawl/lobby-edited.txt`, and the script saves the lobby files
whenever that note has changed. They are also saved if a week has gone by without it, and whenever
the script is run with `-Lobby`. So the lobby in this repository is the lobby as last built, never
more than a week behind in anything else.

`-DryRun` shows what a run would save without saving it.

## Not in the backup (on purpose)

- Player data: who joined, wins, saved game settings, operators, bans.
- Server logs (they contain players' IP addresses).
- `server.properties` (it holds a secret); the `.example` copy is identical apart from that.
- Old source snapshots in `backups/` (this repository's history replaces them).
- Reference projects by other authors that sat next to the plugin source.
