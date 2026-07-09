export const meta = {
  name: 'edt-mcp-autopilot-build',
  description: 'Implements an approved EDT-MCP spec with parallel developers over file-disjoint slices, then runs a review loop where EACH round both COMPILES + runs the unit tests (the build gate = the arbiter) AND applies 3-4 reviewers; build failures and review findings both go to fixers, until the build is green AND no findings remain (max-round safeguard).',
  phases: [
    { title: 'Develop', detail: 'one developer per file-disjoint slice' },
    { title: 'Review', detail: 'build gate (compile+tests) + reviewers -> fixers, until green & clean' },
  ],
}

// args arrives as a JSON STRING in this harness - parse defensively (object/undefined handled too).
//   task            - the task statement (string, required)
//   spec            - the architect spec object (optional, for context)
//   devPartition    - array of slices { slice, files, change, invariant, tests } (required)
//   reviewChecklist - the MUST-ENFORCE checklist text reviewers apply (string)
//   workdir         - ABSOLUTE path of the git worktree the devs must edit (keeps other branches safe)
//   maxRounds       - review-loop cap before escalating (default 4)
//   buildCommand    - the machine-specific build + unit-test command the BUILD GATE runs each round
//                     (e.g. `bash source/compile.sh --java-home <JDK17> --maven-home <maven>`). The
//                     conductor supplies it (kept out of this release-clean script). When omitted, the
//                     build gate is skipped and the loop falls back to reviewers-only (old behaviour).
const A = (() => {
  if (typeof args === 'string') { try { return JSON.parse(args) } catch (e) { return {} } }
  return args || {}
})()
const task = A.task || ''
const spec = A.spec || {}
const partition = A.devPartition || (spec && spec.devPartition) || []
const checklist = A.reviewChecklist ||
  'Apply the project review checklist (review-checklist.md) and the project code rules.'
const workdir = A.workdir || ''
const MAX_ROUNDS = A.maxRounds || 4
const REVIEWERS = partition.length > 4 ? 4 : 3
const buildCommand = A.buildCommand || ''

const WD = workdir
  ? `\n\nWORK ONLY in the git worktree at: ${workdir}\nTreat every file path as relative to that directory (use absolute paths under it). Do NOT edit, create, or git-touch anything outside it.`
  : ''
const GITDIFF = workdir ? `git -C "${workdir}" diff` : 'git diff'

// Resilient agent call: re-spawn a few times if the subagent dies (e.g. a transient API 529).
async function retryAgent(prompt, opts, attempts = 3) {
  for (let i = 1; i <= attempts; i++) {
    const r = await agent(prompt, opts)
    if (r !== null && r !== undefined) return r
    log(`retry ${(opts && opts.label) || (opts && opts.phase) || 'agent'} (attempt ${i}/${attempts})`)
  }
  return null
}

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

// The BUILD GATE's report: did the project compile + pass unit tests, and if not, the precise failures.
const BUILD_SCHEMA = {
  type: 'object',
  properties: {
    ok: { type: 'boolean' },
    failures: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          detail: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['detail'],
      },
    },
  },
  required: ['ok'],
}

// Run the project build + unit tests via a subagent (the workflow sandbox has no shell). Returns
// { ok, failures[] }; a no-op pass when no buildCommand was supplied.
async function buildGate(round) {
  if (!buildCommand) return { ok: true, failures: [] }
  const r = await retryAgent(
    `You are the BUILD GATE for an EDT-MCP change (round ${round}) — the ARBITER the reviewers cannot be, because only a real build settles "does this compile / do the tests pass".${WD}\n\nRun EXACTLY this command from the worktree root and WAIT for it to finish (it is a Maven/Tycho build; first run can take minutes):\n\n${buildCommand}\n\nThen judge the result: set ok=true ONLY if the output contains "BUILD SUCCESS". If it contains "BUILD FAILURE", set ok=false and enumerate EACH failure precisely so a fixer can act:\n- a COMPILE error -> {file: "path:line", detail: the javac/Tycho message, fix: the concrete remedy};\n- a TEST failure/error -> READ the surefire reports (mcp/tests/*/target/surefire-reports/*.txt) and report {file: the test class, detail: "<TestClass>.<method>: <assertion or exception + top stack frame in the tool code>", fix: the concrete remedy}.\nDo NOT edit any file, do NOT commit or run git — only run the build and report. Precision matters: the failing test/method + the exact assert/exception line is what the fixers need.`,
    { phase: 'Review', label: `build-gate:r${round}`, schema: BUILD_SCHEMA })
  return r || { ok: true, failures: [] } // a dead gate agent must not block the loop; reviewers still run
}

if (!partition.length) {
  return { changedFiles: [], reviewLog: [], openProblems: [], rounds: 0, clean: false, error: 'empty devPartition' }
}

// ---- Phase 5: parallel development over file-disjoint slices ---------------------------------
phase('Develop')
const dev = (await parallel(partition.map((s, i) => () =>
  retryAgent(
    `You are DEVELOPER #${i + 1} implementing ONE slice of an approved EDT-MCP spec.\n\nTask: ${task}\n\nYour slice (implement EXACTLY this, nothing outside it):\n${JSON.stringify(s)}${WD}\n\nEdit only the files in your slice - they are disjoint from the other developers. Follow the project conventions and code rules: English-only code, reuse the shared helpers/resolvers, preserve the cited invariant, and add/adjust the slice's tests. Do NOT commit, push, or run git - only edit the files. Return what you changed.`,
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
  // The BUILD GATE (compile + unit tests = the arbiter) runs CONCURRENTLY with the reviewers; both
  // feed the fixers. This is the key lesson: reviewers read the diff but cannot compile, so an
  // unverified API or a broken test churns rounds forever until a real build settles it.
  const gateThunk = () => buildGate(round)
  const reviewerThunks = Array.from({ length: REVIEWERS }, (_, r) => () =>
    retryAgent(
      `You are REVIEWER #${r + 1} (round ${round}) of an EDT-MCP change. Read the working-tree diff (run: ${GITDIFF}) and the changed files.\n\n${checklist}\n\nTask under review: ${task}${WD}\n\nHunt for REAL defects and rule violations (correctness, unattended-safety, bilingual language-CODE keys, schema/execute parity, reflective-form rules, transaction/state-flag timing, error-shape sentinels, English-only / no internal traces, missing ratchet tests). A separate BUILD GATE already runs the compiler + unit tests, so do NOT speculate about whether it compiles — focus on defects a compiler cannot catch. Report only problems you can substantiate, each with a concrete fix. If the change is clean, return an empty problems list.`,
      { phase: 'Review', label: `review:r${round}#${r + 1}`, schema: REVIEW_SCHEMA }))
  const results = await parallel([gateThunk, ...reviewerThunks])
  const build = results[0] || { ok: true, failures: [] }
  const reviewProblems = results.slice(1).filter(Boolean).flatMap((rv) => rv.problems || [])
  const buildProblems = build.ok ? [] : (build.failures || []).map((f) => ({
    severity: 'blocker',
    file: f.file || 'build',
    detail: `BUILD/TEST FAILURE: ${f.detail}`,
    fix: f.fix || 'Make the project build + unit tests pass.',
  }))
  const problems = [...buildProblems, ...reviewProblems]

  reviewLog.push({ round, buildOk: build.ok, problems })
  // Blockers/majors (every build failure is a blocker) always loop; minors are acted on only in round 1.
  const actionable = problems.filter((p) => p.severity !== 'minor' || round === 1)
  log(`Round ${round}: build ${build.ok ? 'GREEN' : 'RED'}, ${problems.length} problems (${actionable.length} actionable)`)

  // Done only when the build is GREEN and no actionable findings remain.
  if (build.ok && !actionable.length) { openProblems = []; clean = true; break }
  openProblems = problems

  // Dispatch fixers grouped by file (file-disjoint -> safe in parallel).
  const byFile = {}
  for (const p of actionable) {
    const f = p.file || 'general'
    ;(byFile[f] = byFile[f] || []).push(p)
  }
  await parallel(Object.keys(byFile).map((f) => () =>
    retryAgent(
      `You are a DEVELOPER fixing problems in an EDT-MCP change (from the reviewers AND the build gate: compile errors + failing unit tests).\n\nTask: ${task}\n\nFile: ${f}\nProblems to fix:\n${JSON.stringify(byFile[f])}${WD}\n\nApply minimal, correct fixes WITHOUT changing behaviour beyond the spec. A BUILD/TEST FAILURE is authoritative — make it pass (fix the code, or the test if its expectation is genuinely wrong). Edit only the affected file(s); do NOT commit or run git. Return when done.`,
      { phase: 'Review', label: `fix:r${round}:${f.split(/[\\/]/).pop()}` })))
}

return {
  changedFiles,
  reviewLog,
  openProblems,
  rounds: round > MAX_ROUNDS ? MAX_ROUNDS : round,
  clean,
}
