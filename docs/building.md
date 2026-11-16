# Building from source

The plugin is a Maven/Tycho project under [mcp/](../mcp/). CI builds it via [.github/workflows/build.yml](../.github/workflows/build.yml); the same flow can be run locally with [source/compile.sh](../source/compile.sh).

## Prerequisites

- JDK 25 (e.g. Temurin) - Tycho 5 needs JDK 21+ to run and reads the platform's Java 25 class files; the plugin itself is still compiled to Java 17
- Apache Maven 3.9+ (no `mvnw` wrapper is committed — install Maven manually or via a package manager: `winget`, Homebrew, `apt`, SDKMAN, etc.)
- `bash` (Git Bash on Windows works) and either `zip` or the `jar` binary that ships with the JDK
- Network access to `https://edt.1c.ru/`, `https://download.eclipse.org/` and Maven Central — Tycho downloads the EDT p2 repository and Eclipse SDK on the first run (hundreds of MB, cached afterwards under `~/.m2/`)

## Quick start

```bash
# from the repo root
bash source/compile.sh --skip-tests
```

Output:

```
source/dist/MCP-EDT.v<VERSION>.zip
```

This is a valid p2 update site — install via EDT → *Help → Install New Software → Add → Archive…*.

## Script options

`source/compile.sh` accepts every path as a flag (with matching environment-variable fallback) so it can be driven from CI or run against an out-of-tree checkout:

| Flag | ENV fallback | Default | Meaning |
|---|---|---|---|
| `--skip-tests` | — | off | Skip Maven Surefire tests |
| `--version X.Y.Z` | — | parsed from `README.md`, falls back to `dev` | Version label used in the output zip name |
| `--archive-prefix PREFIX` | — | `MCP-EDT.v` | Archive name prefix (final name: `<prefix><version>.zip`) |
| `--project-root PATH` | `EDT_MCP_PROJECT_ROOT` | parent of script dir | Repo root containing `mcp/` |
| `--mcp-dir PATH` | — | `<project-root>/mcp` | Maven project directory |
| `--repo-dir PATH` | — | `<project-root>/mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository` | Tycho p2 output to repackage |
| `--output-dir PATH` | `EDT_MCP_OUTPUT_DIR` | `<script-dir>/dist` | Where the final zip lands |
| `--java-home PATH` | `JAVA_HOME` | — | JDK 25 home; if set, prepended to `PATH` for Maven |
| `--maven-home PATH` | `MAVEN_HOME` / `M2_HOME` | — | Maven home (uses `<maven-home>/bin/mvn`); otherwise falls back to `mvn` on `PATH` |
| `-h`, `--help` | — | — | Show help |

## Examples

```bash
# Self-contained invocation, no env tweaks required
bash source/compile.sh \
    --java-home "/c/Program Files/Java/jdk-25" \
    --maven-home /d/Soft/maven \
    --skip-tests \
    --version 1.27.1

# Drop the artifact somewhere else
bash source/compile.sh --output-dir /tmp/edt-mcp-builds

# Same, configured via environment
JAVA_HOME="/c/Program Files/Java/jdk-25" \
MAVEN_HOME=/d/Soft/maven \
EDT_MCP_OUTPUT_DIR=/tmp/edt-mcp-builds \
bash source/compile.sh
```

## Notes

- A full first build pulls the EDT 2026.2 p2 repository (`mcp/targets/default/default.target`) and the Eclipse 2025-12 release — expect several minutes. Subsequent builds run in ~1 minute thanks to the local p2 cache.
- `bash source/verify-oldest-platform.sh <edt-install-dir>` is an optional local check, not a release requirement: it compiles the same sources against an installed older EDT and so finds calls to APIs that release does not have. It takes the defaults for 2026.1, and `--ee JavaSE-25 --keep-svg` for 2026.2.0.
- The output zip uses forward-slash entries (produced by `jar` when `zip` is unavailable) so it installs cleanly on both Windows and Linux EDT instances.
- `source/dist/` is gitignored; only the script itself is tracked.

