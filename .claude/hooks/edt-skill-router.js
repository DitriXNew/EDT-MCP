#!/usr/bin/env node
/*
 * EDT-MCP path-scoped skill router (PostToolUse hook).
 *
 * When Claude edits/writes a file under a sensitive area of the plugin, this
 * hook injects a short reminder pointing at the relevant project skill, so the
 * right conventions get loaded even when the skill wasn't auto-discovered by
 * description alone.
 *
 * Mechanism: Claude Code hook matchers filter by TOOL NAME only; file-path
 * scoping is done here in code (reading tool_input.file_path from stdin).
 * The hook only ever emits `additionalContext` — it never blocks a tool call.
 *
 * Registered in .claude/settings.json under hooks.PostToolUse (matcher Edit|Write).
 */
'use strict';

function readStdin() {
  try {
    return require('fs').readFileSync(0, 'utf8');
  } catch (e) {
    return '';
  }
}

function main() {
  let data;
  try {
    data = JSON.parse(readStdin() || '{}');
  } catch (e) {
    process.exit(0); // never block on parse failure
  }

  const input = data.tool_input || {};
  // Edit/Write use file_path; some tools may carry path/notebook_path.
  const raw = input.file_path || input.path || input.notebook_path || '';
  if (!raw) process.exit(0);

  const p = String(raw).replace(/\\/g, '/'); // normalize Windows separators
  const base = p.split('/').pop() || '';

  const tips = [];

  const inImpl = /\/tools\/impl\//.test(p);
  const isMetadata =
    /\/tools\/metadata\//.test(p) ||
    /Metadata/.test(base) ||
    /Synonym/.test(base) ||
    /Subsystem/.test(base) ||
    /Translation/.test(base);
  const isBslCode =
    /(Module|Method|Symbol|Reference|Definition|ContentAssist|SearchInCode|Query|CallHierarchy|Bsl)/.test(base);
  const inAssociations = /\/(tags|groups)\//.test(p);
  const inE2eTests = /\/tests\/e2e\//.test(p);
  // Adding/removing a tool touches the registrar and (for new EDT API) the MANIFEST — the
  // strongest, lowest-noise signal that the full add-a-tool checklist applies.
  const isToolWiring = /^BuiltInToolRegistrar\.java$/.test(base) || /^MANIFEST\.MF$/.test(base);
  const isYaxunit = /yaxunit/i.test(base);
  const isBuild = /^(compile\.sh|pom\.xml)$/.test(base);

  if (inE2eTests) {
    // e2e test files (Python, tests/e2e/) have names like test_get_module_structure.py
    // that would false-match the Java-plugin rules below, so the e2e tip is EXCLUSIVE.
    tips.push('automated black-box e2e suite — use /edt-mcp-e2e-testing (git-fixture isolation; happy + negative + error-quality; for write-metadata assert BOTH model read-back AND on-disk structure via poll_diff_contains; anti-cheat mutation thinking; full guide: tests/e2e/SKILL.md)');
  } else {
    if (inImpl) {
      tips.push('cross-tool contract — use /edt-mcp-tool-conventions (param naming, ToolResult.error, shared resolvers, schema↔code)');
    }
    if (isMetadata || isBslCode) {
      tips.push('1C ru/en correctness — use /edt-mcp-bilingual (synonym keyed by language CODE, resolve by Name, dialect-aware vs literal)');
    }
    if (inAssociations) {
      tips.push('tags/* and groups/* must share a common base, not diverge — see /edt-mcp-architecture (extract-tags-groups-shared-base)');
    }
    if (isToolWiring) {
      tips.push('adding/removing a tool? follow the WHOLE checklist — use /edt-mcp-new-tool (class+schema+getGuide, registry, MANIFEST Import-Package, the unit + e2e coverage ratchets, golden regen, README, and the compile→Opus→redeploy→live contour)');
    }
    if (isYaxunit) {
      tips.push('YAXUnit run/debug — use /edt-mcp-yaxunit (the YAxUnit.cfe engine must be loaded in the infobase; the "tests" extension; launch/update modal gotchas)');
    }
    if (isBuild) {
      tips.push('build & validate locally — use /edt-mcp-build-test (compile.sh with JDK17 + Maven off PATH; tests need the p2 target platform; verify the DEPLOYED jar actually carries your change)');
    }
  }

  if (tips.length === 0) process.exit(0);

  const msg =
    'EDT-MCP convention reminder for ' + base + ':\n- ' + tips.join('\n- ') +
    '\nDescribe target architecture, not the current duplicated state (refactor in progress; see .devtool/features/).';

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext: msg,
      },
    })
  );
  process.exit(0);
}

main();
