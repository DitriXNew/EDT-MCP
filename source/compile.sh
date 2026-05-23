#!/usr/bin/env bash
# Build script for MCP-EDT plugin.
#
# Runs Maven/Tycho build, then repackages the p2 repository into a versioned
# zip archive (MCP-EDT.v<version>.zip). All paths can be overridden via flags
# or environment variables.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEFAULT_PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DEFAULT_OUTPUT_DIR="$SCRIPT_DIR/dist"
DEFAULT_REPO_SUBPATH="mcp/repositories/com.ditrix.edt.mcp.server.repository/target/repository"
DEFAULT_ARCHIVE_PREFIX="MCP-EDT.v"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Build the EDT MCP plugin and produce a versioned p2 update-site zip.

Build options:
  --skip-tests                Skip running Maven Surefire tests
  --version X.Y.Z             Version label for the output archive
                              (default: parsed from README.md, falls back to 'dev')
  --archive-prefix PREFIX     Archive name prefix
                              (default: ${DEFAULT_ARCHIVE_PREFIX} → ${DEFAULT_ARCHIVE_PREFIX}X.Y.Z.zip)

Path options (each has matching ENV fallback):
  --project-root PATH         Repo root (contains 'mcp/')         [\$EDT_MCP_PROJECT_ROOT]
                              default: $DEFAULT_PROJECT_ROOT
  --mcp-dir PATH              Maven project dir
                              default: <project-root>/mcp
  --repo-dir PATH             Tycho p2 repository output dir
                              default: <project-root>/$DEFAULT_REPO_SUBPATH
  --output-dir PATH           Where to drop the final zip          [\$EDT_MCP_OUTPUT_DIR]
                              default: $DEFAULT_OUTPUT_DIR

Toolchain options:
  --java-home PATH            JDK 17 home                          [\$JAVA_HOME]
  --maven-home PATH           Maven home (uses <maven-home>/bin/mvn)  [\$MAVEN_HOME / \$M2_HOME]
                              falls back to 'mvn' on PATH

Other:
  -h, --help                  Show this help

Examples:
  $(basename "$0") --skip-tests
  $(basename "$0") --version 1.27.1 --output-dir D:/builds
  $(basename "$0") --java-home "/c/Program Files/Java/jdk-17" --maven-home /d/Soft/maven
  JAVA_HOME=/c/jdk17 MAVEN_HOME=/d/Soft/maven $(basename "$0")
EOF
}

log() { echo "[compile] $*"; }
die() { echo "Error: $*" >&2; exit 1; }

normalize_path() {
    local p="$1"
    if [[ -z "$p" ]]; then
        printf ''
        return
    fi
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$p" 2>/dev/null || printf '%s' "$p"
    else
        printf '%s' "$p"
    fi
}

to_windows_path() {
    local p="$1"
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$p"
    else
        printf '%s' "$p"
    fi
}

parse_version_from_readme() {
    local readme="$1"
    if [[ ! -f "$readme" ]]; then
        return 1
    fi
    local version
    version=$(grep -oP '<summary><strong>\K[\d.]+(?=</strong>)' "$readme" | head -n 1 || true)
    if [[ -n "$version" ]]; then
        printf '%s' "$version"
        return 0
    fi
    return 1
}

resolve_mvn() {
    local maven_home="$1"
    if [[ -n "$maven_home" ]]; then
        local mvn="$maven_home/bin/mvn"
        if [[ -x "$mvn" ]] || [[ -x "$mvn.cmd" ]]; then
            printf '%s' "$mvn"
            return
        fi
        die "Maven not found in --maven-home: $maven_home"
    fi
    if command -v mvn >/dev/null 2>&1; then
        command -v mvn
        return
    fi
    die "mvn not found on PATH and no --maven-home / MAVEN_HOME provided"
}

run_maven() {
    local mvn="$1"; shift
    local mcp_dir="$1"; shift
    local skip_tests="$1"; shift

    local -a cmd=("$mvn" clean verify --batch-mode -T 1C)
    if [[ "$skip_tests" == "true" ]]; then
        cmd+=(-DskipTests)
    fi

    log "Working directory: $mcp_dir"
    log "Running: ${cmd[*]}"
    echo

    (cd "$mcp_dir" && "${cmd[@]}")
}

package_zip() {
    local repo_dir="$1"
    local output_dir="$2"
    local archive_name="$3"

    [[ -d "$repo_dir" ]] || die "repository directory not found: $repo_dir"

    mkdir -p "$output_dir"
    local zip_path="$output_dir/$archive_name"
    rm -f "$zip_path"

    if command -v zip >/dev/null 2>&1; then
        (cd "$repo_dir" && zip -r -q "$zip_path" .)
    elif command -v jar >/dev/null 2>&1; then
        (cd "$repo_dir" && jar --create --file "$(to_windows_path "$zip_path")" --no-manifest .)
    else
        die "no zip tool found (need 'zip' or 'jar' from JDK)"
    fi

    printf '%s' "$zip_path"
}

main() {
    local skip_tests="false"
    local version=""
    local archive_prefix="$DEFAULT_ARCHIVE_PREFIX"
    local project_root="${EDT_MCP_PROJECT_ROOT:-}"
    local mcp_dir=""
    local repo_dir=""
    local output_dir="${EDT_MCP_OUTPUT_DIR:-}"
    local java_home_arg=""
    local maven_home_arg=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --skip-tests) skip_tests="true"; shift ;;
            --version)         [[ $# -ge 2 ]] || die "--version needs a value"; version="$2"; shift 2 ;;
            --version=*)       version="${1#*=}"; shift ;;
            --archive-prefix)  [[ $# -ge 2 ]] || die "--archive-prefix needs a value"; archive_prefix="$2"; shift 2 ;;
            --archive-prefix=*) archive_prefix="${1#*=}"; shift ;;
            --project-root)    [[ $# -ge 2 ]] || die "--project-root needs a value"; project_root="$2"; shift 2 ;;
            --project-root=*)  project_root="${1#*=}"; shift ;;
            --mcp-dir)         [[ $# -ge 2 ]] || die "--mcp-dir needs a value"; mcp_dir="$2"; shift 2 ;;
            --mcp-dir=*)       mcp_dir="${1#*=}"; shift ;;
            --repo-dir)        [[ $# -ge 2 ]] || die "--repo-dir needs a value"; repo_dir="$2"; shift 2 ;;
            --repo-dir=*)      repo_dir="${1#*=}"; shift ;;
            --output-dir)      [[ $# -ge 2 ]] || die "--output-dir needs a value"; output_dir="$2"; shift 2 ;;
            --output-dir=*)    output_dir="${1#*=}"; shift ;;
            --java-home)       [[ $# -ge 2 ]] || die "--java-home needs a value"; java_home_arg="$2"; shift 2 ;;
            --java-home=*)     java_home_arg="${1#*=}"; shift ;;
            --maven-home)      [[ $# -ge 2 ]] || die "--maven-home needs a value"; maven_home_arg="$2"; shift 2 ;;
            --maven-home=*)    maven_home_arg="${1#*=}"; shift ;;
            -h|--help)         usage; exit 0 ;;
            *)                 echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
        esac
    done

    project_root="$(normalize_path "${project_root:-$DEFAULT_PROJECT_ROOT}")"
    [[ -d "$project_root" ]] || die "project root not found: $project_root"

    mcp_dir="$(normalize_path "${mcp_dir:-$project_root/mcp}")"
    [[ -f "$mcp_dir/pom.xml" ]] || die "no pom.xml in --mcp-dir: $mcp_dir"

    repo_dir="$(normalize_path "${repo_dir:-$project_root/$DEFAULT_REPO_SUBPATH}")"
    output_dir="$(normalize_path "${output_dir:-$DEFAULT_OUTPUT_DIR}")"

    local java_home
    java_home="$(normalize_path "${java_home_arg:-${JAVA_HOME:-}}")"
    if [[ -n "$java_home" ]]; then
        [[ -x "$java_home/bin/java" ]] || [[ -x "$java_home/bin/java.exe" ]] \
            || die "java not found in --java-home: $java_home"
        export JAVA_HOME="$(to_windows_path "$java_home")"
        export PATH="$java_home/bin:$PATH"
    fi

    local maven_home
    maven_home="$(normalize_path "${maven_home_arg:-${MAVEN_HOME:-${M2_HOME:-}}}")"
    local mvn
    mvn="$(resolve_mvn "$maven_home")"

    if [[ -z "$version" ]]; then
        if v="$(parse_version_from_readme "$project_root/README.md")"; then
            version="$v"
        else
            echo "Warning: could not parse version from README.md, using 'dev'" >&2
            version="dev"
        fi
    fi

    log "JAVA_HOME      : ${JAVA_HOME:-<unset>}"
    log "Maven          : $mvn"
    log "Project root   : $project_root"
    log "MCP dir        : $mcp_dir"
    log "Repo dir (out) : $repo_dir"
    log "Output dir     : $output_dir"
    log "Version        : $version"
    log "Skip tests     : $skip_tests"
    echo

    run_maven "$mvn" "$mcp_dir" "$skip_tests"

    local archive_name="${archive_prefix}${version}.zip"
    local zip_path
    zip_path="$(package_zip "$repo_dir" "$output_dir" "$archive_name")"

    local size_bytes size_kb
    size_bytes=$(wc -c < "$zip_path" | tr -d ' ')
    size_kb=$(awk "BEGIN { printf \"%.1f\", $size_bytes / 1024 }")

    echo
    log "Archive created: $zip_path"
    log "Size           : ${size_kb} KB"
}

main "$@"
