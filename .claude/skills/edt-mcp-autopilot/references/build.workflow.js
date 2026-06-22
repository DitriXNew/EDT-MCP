export const meta = {
  name: 'edt-mcp-autopilot-build',
  description: 'Implements an approved EDT-MCP spec with parallel developers over file-disjoint slices, then runs a 3-4 reviewer loop that re-runs until clean (problems -> fixers -> re-review), with a max-round safeguard.',
  phases: [
    { title: 'Develop', detail: 'one developer per file-disjoint slice' },
    { title: 'Review', detail: 'cyclic reviewers until clean' },
  ],
}

// args:
//   task            - the task statement (string, required)
//   spec            - the architect spec object (optional, for context)
//   devPartition    - array of slices { slice, files, change, invariant, tests } (required)
//   reviewChecklist - the MUST-ENFORCE checklist text reviewers apply (string)
//   maxRounds       - review-loop cap before escalating (default 4)
const task = (args && args.task) || ''
const spec = (args && args.spec) || {}
const partition = (args && args.devPartition) || (spec && spec.devPartition) || []
const checklist = (args && args.reviewChecklist) ||
  'Apply the project review checklist (review-checklist.md) and the project code rules.'
const MAX_ROUNDS = (args && args.maxRounds) || 4
const REVIEWERS = partition.length > 4 ? 4 : 3

const CHANGE_SCHEMA = {
  type: 'object',
  properties: {
    slice: { type: 'string' },
    changedFiles: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
    notes: { type: 'string' },
  },
  required: ['summary'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    problems: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          file: { type: 'string' },
          detail: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'detail'],
      },
    },
  },
  required: ['problems'],
}

if (!partition.length) {
  return { changedFiles: [], reviewLog: [], openProblems: [], rounds: 0, clean: false, error: 'empty devPartition' }
}

// ---- Phase 5: parallel development over file-disjoint slices ---------------------------------
phase('Develop')
const dev = (await parallel(partition.map((s, i) => () =>
  agent(
    `You are DEVELOPER #${i + 1} implementing ONE slice of an approved EDT-MCP spec.\n\nTask: ${task}\n\nYour slice (implement EXACTLY this, nothing outside it):\n${JSON.stringify(s)}\n\nEdit only the files in your slice - they are disjoint from the other developers. Follow the project conventions and code rules: English-only code, reuse the shared helpers/resolvers, preserve the cited invariant, and add/adjust the slice's tests. Do NOT commit, push, or run git - only edit the files. Return what you changed.`,
    { phase: 'Develop', label: `dev#${i + 1}`, schema: CHANGE_SCHEMA }))))
  .filter(Boolean)
const changedFiles = dev.flatMap((d) => d.changedFiles || [])
log(`Developed ${dev.length}/${partition.length} slices, ${changedFiles.length} files touched`)

// ---- Phase 6: review loop (problems -> fixers -> re-review) until clean ----------------------
phase('Review')
const reviewLog = []
let openProblems = []
let round = 0
let clean = false

for (round = 1; round <= MAX_ROUNDS; round++) {
  const problems = (await parallel(Array.from({ length: REVIEWERS }, (_, r) => () =>
    agent(
      `You are REVIEWER #${r + 1} (round ${round}) of an EDT-MCP change. Read the working-tree diff (run: git diff) and the changed files.\n\n${checklist}\n\nTask under review: ${task}\n\nHunt for REAL defects and rule violations (correctness, unattended-safety, bilingual language-CODE keys, schema/execute parity, reflective-form rules, transaction/state-flag timing, error-shape sentinels, English-only / no internal traces, missing ratchet tests). Report only problems you can substantiate, each with a concrete fix. If the change is clean, return an empty problems list.`,
      { phase: 'Review', label: `review:r${round}#${r + 1}`, schema: REVIEW_SCHEMA }))))
    .filter(Boolean)
    .flatMap((rv) => rv.problems)

  reviewLog.push({ round, problems })
  // Blockers/majors always loop; minors are acted on only in round 1, then recorded.
  const actionable = problems.filter((p) => p.severity !== 'minor' || round === 1)
  log(`Round ${round}: ${problems.length} problems (${actionable.length} actionable)`)

  if (!actionable.length) { openProblems = []; clean = true; break }
  openProblems = problems

  // Dispatch fixers grouped by file (file-disjoint -> safe in parallel).
  const byFile = {}
  for (const p of actionable) {
    const f = p.file || 'general'
    ;(byFile[f] = byFile[f] || []).push(p)
  }
  await parallel(Object.keys(byFile).map((f) => () =>
    agent(
      `You are a DEVELOPER fixing review problems in an EDT-MCP change.\n\nTask: ${task}\n\nFile: ${f}\nProblems to fix:\n${JSON.stringify(byFile[f])}\n\nApply minimal, correct fixes that satisfy the reviewers WITHOUT changing behaviour beyond the spec. Edit only the affected file(s); do NOT commit or run git. Return when done.`,
      { phase: 'Review', label: `fix:r${round}:${f.split(/[\\/]/).pop()}` })))
}

return {
  changedFiles,
  reviewLog,
  openProblems,
  rounds: round > MAX_ROUNDS ? MAX_ROUNDS : round,
  clean,
}
