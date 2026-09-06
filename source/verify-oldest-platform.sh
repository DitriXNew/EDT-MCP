#!/usr/bin/env bash
#
# Compiles the bundle against the OLDEST supported EDT, to prove one artifact still loads there.
#
# Why this exists: the build compiles against the newest EDT target platform, while the bundle
# ships Bundle-RequiredExecutionEnvironment JavaSE-17 and lower-bound package imports that a newer
# EDT satisfies just as well as an older one. Those declarations cannot express "no method that
# only the newer EDT has": the compiler will happily bind a signature added in the newer release,
# and the older EDT then resolves the bundle and fails at the call with NoSuchMethodError. The one
# check that settles it is to compile the same sources against the older platform - which is what
# this script does, using a local EDT installation as the target platform.
#
# It is a LOCAL gate, not a CI job: 1C publishes only the current service release of each EDT
# major under https://edt.1c.ru/downloads/releases/ruby/<major>/, so an older platform is
# reproducible from an installation on disk, not from a URL.
#
# Usage:
#   bash source/verify-oldest-platform.sh /path/to/1c-edt-<oldest>-x86_64 [--java-home DIR] [--maven-home DIR]
#
# The working tree is restored on every exit path; the script fails loudly if it cannot restore.

set -u

INSTALL=""
JAVA_HOME_ARG=""
MAVEN_HOME_ARG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --java-home) JAVA_HOME_ARG="$2"; shift 2 ;;
    --maven-home) MAVEN_HOME_ARG="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) INSTALL="$1"; shift ;;
  esac
done

if [ -z "$INSTALL" ]; then
  echo "usage: $0 <edt-install-dir> [--java-home DIR] [--maven-home DIR]" >&2
  exit 2
fi
if [ ! -d "$INSTALL/plugins" ]; then
  echo "not an EDT installation (no plugins/ directory): $INSTALL" >&2
  exit 2
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$REPO/mcp/targets/default/default.target"
BOM="$REPO/mcp/bom/pom.xml"
BACKUP="$(mktemp -d)"

cp "$TARGET" "$BACKUP/default.target"
cp "$BOM" "$BACKUP/pom.xml"

restore()
{
  cp "$BACKUP/default.target" "$TARGET" && cp "$BACKUP/pom.xml" "$BOM" || {
    echo "FAILED TO RESTORE the working tree - originals are in $BACKUP" >&2
    exit 3
  }
  rm -rf "$BACKUP"
  echo "-- working tree restored"
}
trap restore EXIT INT TERM

# The probe target: every bundle of the installed EDT, and nothing from the network.
# The path must be written the way the JVM reads it: under Git Bash a POSIX /d/... path is
# resolved by Tycho against the target file's drive (D:\d\...), the location silently comes up
# empty, and the build then fails with a confusing "missing requirement" instead of "no such
# directory". cygpath -m produces the mixed C:/... form the target file needs.
if command -v cygpath >/dev/null 2>&1; then
  PLUGINS="$(cygpath -m "$INSTALL/plugins")"
else
  PLUGINS="$(cd "$INSTALL/plugins" && pwd)"
fi
cat > "$TARGET" <<XML
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?pde version="3.8"?><target name="Oldest supported EDT (local probe)" sequenceNumber="1">
<locations>
<location path="$PLUGINS" type="Directory"/>
</locations>
</target>
XML

# Two things in the BOM belong to the NEWER platform and must not decide this compile:
#   - org.eclipse.swt.svg is an Eclipse 4.38 bundle, pulled in only to assemble the test runtime;
#   - the resolution execution environment is the newer platform's JavaSE-25.
awk '
  /<requirement>/ { buffering = 1; buffer = ""; }
  buffering { buffer = buffer $0 "\n";
              if ($0 ~ /<\/requirement>/)
              {
                  buffering = 0;
                  if (buffer !~ /org\.eclipse\.swt\.svg/) { printf "%s", buffer; }
              }
              next; }
  { sub(/<executionEnvironment>JavaSE-25<\/executionEnvironment>/,
        "<executionEnvironment>JavaSE-17</executionEnvironment>"); print; }
' "$BACKUP/pom.xml" > "$BOM"

grep -q "<executionEnvironment>JavaSE-17</executionEnvironment>" "$BOM" \
  || { echo "the BOM patch did not replace the execution environment" >&2; exit 3; }
if grep -q "org.eclipse.swt.svg" "$BOM"; then
  echo "the BOM patch did not drop the newer platform's SVG requirement" >&2
  exit 3
fi

[ -n "$JAVA_HOME_ARG" ] && export JAVA_HOME="$JAVA_HOME_ARG"
MVN="mvn"
[ -n "$MAVEN_HOME_ARG" ] && MVN="$MAVEN_HOME_ARG/bin/mvn"

echo "-- compiling against $INSTALL"
cd "$REPO/mcp" || exit 1
LOG="$BACKUP/maven.log"
"$MVN" -B -ntp -pl targets/default,bundles/com.ditrix.edt.mcp.server clean compile 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}

# An unreadable location does not fail the build - it produces an EMPTY target platform and then
# a "missing requirement" for the first bundle asked for, which reads like a real finding. Refuse
# to report either outcome from a target platform that was never populated.
if grep -q "target resolution might be incomplete" "$LOG"; then
  echo
  echo "INCONCLUSIVE: Tycho could not read $PLUGINS, so nothing was checked." >&2
  exit 4
fi

if [ $STATUS -eq 0 ]; then
  echo
  echo "OK: every API the bundle references exists in $(basename "$INSTALL")."
else
  echo
  echo "FAILED: the bundle references something this EDT does not have (see the errors above)." >&2
fi
exit $STATUS
