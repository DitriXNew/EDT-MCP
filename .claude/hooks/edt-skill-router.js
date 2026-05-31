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

  if (inImpl) {
    tips.push('cross-tool contract — use /edt-mcp-tool-conventions (param naming, ToolResult.error, shared resolvers, schema↔code)');
  }
  if (isMetadata || isBslCode) {
    tips.push('1C ru/en correctness — use /edt-mcp-bilingual (synonym keyed by language CODE, resolve by Name, dialect-aware vs literal)');
  }
  if (inAssociations) {
    tips.push('tags/* and groups/* must share a common base, not diverge — see /edt-mcp-architecture (extract-tags-groups-shared-base)');
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
