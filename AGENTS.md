# AGENTS.md

## Build & run
- Build: `gradle build` (output at `build/libs/slcp-<version>.jar`)
- Requires JDK 21 (enforced in `build.gradle` via `options.release = 21`)
- No Gradle wrapper committed; use system `gradle` command
- No test suite exists

## Architecture
- Minecraft 1.21.11 Fabric mod using Fabric Loom plugin
- **Entrypoints** (declared in `fabric.mod.json`):
  - `main` → `SLCPMod.java` — loads config + downloads on server/client start
  - `client` → `SLCPModClient.java` — registers `/slcp redownload` command
  - `modmenu` → `SLCPModMenuIntegration.java` — ModMenu config screen (optional dep)
- **Core logic**: `DownloadManager.java` (HttpURLConnection), `SLCPConfig.java` (JSON config load/save/parse)
- **GUI**: `SLCPConfigScreen.java` — a single "Redownload" button screen

## Config format
- Config lives at `<mc config dir>/slcp/config.json`
- On first run, the default JAR resource `/slcp_config.json` is copied there
- Non-standard JSON array structure — each entry is `{"name": {"url": "...", "output": "..."}}`:
  ```json
  [
      {
          "name": {
              "url": "https://...",
              "output": "./"
          }
      }
  ]
  ```
- The `output` path is relative to `run/` (the MC working directory)
- Save uses Gson pretty-print; parse is robust to malformed entries

## HTTP
- Uses raw `java.net.HttpURLConnection` (no Apache/OkHttp)
- Manual redirect handling (up to 5 hops), `InstanceFollowRedirects` is `false`
- User-Agent header value is at `DownloadManager.java:22`
- Timeouts: connect 30s, read 300s
