export const meta = {
  name: 'edt-mcp-autopilot-discover',
  description: 'Research -> critics -> architect for an EDT-MCP task: produces an implementation spec, a file-disjoint developer partition, and any escalation questions for the human.',
  phases: [
    { title: 'Docs', detail: '2 documentation researchers' },
    { title: 'Code research', detail: 'size-scaled waves, loop-until-dry' },
    { title: 'Critique', detail: 'a small fixed critic panel batch-reviews each wave' },
    { title: 'Architect', detail: 'synthesise surviving findings into a spec + dev partition' },
  ],
}

// args arrives as a JSON STRING in this harness - parse defensively (object/undefined also handled).
//   task   - the task statement / issue text (string, required)
//   size   - 'small' | 'medium' | 'large' (scales fan-out; default 'medium')
//   dryRun - when true, agents only outline what they WOULD do (no deep work)
const A = (() => {
  if (typeof args === 'string') { try { return JSON.parse(args) } catch (e) { return { task: args } } }
  return args || {}
})()
const task = (A.task && String(A.task).trim()) || ''
const size = A.size || 'medium'
const dryRun = !!A.dryRun

// SAFETY: never fan out on an empty task (this is what caused a 210-agent no-op run).
if (!task || task === 'No task provided.') {
  log('ABORT: no task provided to discover.workflow - nothing to research.')
  return {
    spec: null,
    devPartition: [],
    escalations: [{ question: 'No task was provided to the discover workflow. Re-run with args.task set.', options: 'Pass {task: "<the task/issue text>"} as args.', why: 'The workflow refuses to fan out agents on an empty task.' }],
  }
}

// Fan-out sizing by task size, kept deliberately small. budget tightens it further.
const WAVE = { small: 3, medium: 4, large: 6 }[size] || 4
const MAX_WAVES = { small: 2, medium: 2, large: 3 }[size] || 2
const PANEL = size === 'large' ? 3 : 2          // critics per wave (fixed panel, NOT per-finding)
const MAX_FINDINGS_PER_AGENT = 6
const MAX_FRESH_PER_WAVE = 24                    // bound the critique batch + its JSON size
const tight = budget && budget.total && budget.remaining() < 120000

const SKIP_BIAS =
  ' Report only what you verified against the real code/docs; be skeptical. Keep each finding tight' +
  ' (a sentence or two). No behaviour change is implied here - if something looks meaningful or risky, flag it.'

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      maxItems: MAX_FINDINGS_PER_AGENT,
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          detail: { type: 'string' },
          evidence: { type: 'string' },
          relevance: { type: 'string' },
        },
        required: ['title', 'detail'],
      },
    },
  },
  required: ['findings'],
}

// One critic call returns a verdict for EVERY finding in the batch (by index).
const BATCH_VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    verdicts: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          index: { type: 'integer' },
          verdict: { type: 'string', enum: ['CONFIRMED', 'REFUTED', 'REWORK'] },
          reason: { type: 'string' },
        },
        required: ['index', 'verdict'],
      },
    },
  },
  required: ['verdicts'],
}

const SPEC_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string' },
    acceptanceCriteria: { type: 'array', items: { type: 'string' } },
    approach: { type: 'string' },
    devPartition: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          slice: { type: 'string' },
          files: { type: 'array', items: { type: 'string' } },
          change: { type: 'string' },
          invariant: { type: 'string' },
          tests: { type: 'string' },
        },
        required: ['slice', 'change'],
      },
    },
    recommendedDevs: { type: 'integer' },
    testPlan: { type: 'string' },
    escalations: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          question: { type: 'string' },
          options: { type: 'string' },
          why: { type: 'string' },
        },
        required: ['question'],
      },
    },
  },
  required: ['summary', 'acceptanceCriteria', 'approach', 'devPartition'],
}

// ---- Phase 1: documentation research (2 agents) ----------------------------------------------
phase('Docs')
const docPrompts = [
  `You are a DOCUMENTATION researcher for this EDT-MCP task:\n\n${task}\n\nStudy ONLY authoritative documentation (the local apidocs Javadoc index, the official EDT apidocs site, the project docs/ knowledge base, plugin docs). Report at most ${MAX_FINDINGS_PER_AGENT} concrete API facts that bear on the task: exact class/method/enum names and signatures, constraints, version differences. Cite where each fact comes from.${dryRun ? ' DRY RUN: only outline what you would look up.' : ''}${SKIP_BIAS}`,
  `You are a PRECEDENT researcher for this EDT-MCP task:\n\n${task}\n\nFind how the project ALREADY does similar things: existing tools, shared helpers/resolvers, conventions, prior PRs/issues, relevant project skills. Report at most ${MAX_FINDINGS_PER_AGENT} reusable building blocks with their file paths so we reuse instead of reinventing.${dryRun ? ' DRY RUN: only outline.' : ''}${SKIP_BIAS}`,
]
const docFindings = (await parallel(docPrompts.map((p) => () =>
  agent(p, { phase: 'Docs', schema: FINDINGS_SCHEMA }))))
  .filter(Boolean)
  .flatMap((r) => r.findings || [])
log(`Docs: ${docFindings.length} findings`)

// ---- Phases 2-3: code-research waves + a fixed critic panel (loop-until-dry, bounce-back) -----
const ANGLES = [
  'data flow and exactly where the change must land',
  'existing patterns / shared helpers to reuse',
  'edge cases, error paths and unattended-safety',
  'tests and build ratchets that will gate the change',
  'cross-file callers and wire/contract impact',
  'bilingual (RU/EN) resolution paths',
]

const seen = new Set()
const confirmed = []
const keyOf = (f) =>
  `${(f.file || '').toLowerCase().trim()}::${(f.title || '').toLowerCase().trim().slice(0, 80)}`
let reworkNotes = ''

for (let wave = 1; wave <= MAX_WAVES; wave++) {
  if (tight) { log('budget tight - stopping research waves'); break }
  phase('Code research')
  const lenses = Array.from({ length: WAVE }, (_, i) => ANGLES[(i + (wave - 1) * WAVE) % ANGLES.length])
  const found = (await parallel(lenses.map((lens, i) => () =>
    agent(
      `You are CODE researcher #${i + 1} (wave ${wave}) for this EDT-MCP task:\n\n${task}\n\nInvestigate the PRODUCT CODE through this lens: ${lens}. Use ripgrep and read the real files. Report at most ${MAX_FINDINGS_PER_AGENT} concrete, file-anchored findings (what is true, where, why it matters).${reworkNotes ? ' Critics asked to re-examine: ' + reworkNotes : ''}${dryRun ? ' DRY RUN: only outline.' : ''}${SKIP_BIAS}`,
      { phase: 'Code research', label: `research:w${wave}#${i + 1}`, schema: FINDINGS_SCHEMA }))))
    .filter(Boolean)
    .flatMap((r) => r.findings || [])

  let fresh = found.filter((f) => !seen.has(keyOf(f)))
  fresh.forEach((f) => seen.add(keyOf(f)))
  if (fresh.length > MAX_FRESH_PER_WAVE) {
    log(`Wave ${wave}: ${fresh.length} new findings -> capping critique to ${MAX_FRESH_PER_WAVE}`)
    fresh = fresh.slice(0, MAX_FRESH_PER_WAVE)
  }
  log(`Wave ${wave}: ${found.length} found, ${fresh.length} new (critique batch)`)
  if (!fresh.length) break

  // FIXED critic panel: each critic reviews the WHOLE fresh batch and returns a verdict per index.
  reworkNotes = ''
  const numbered = fresh.map((f, i) => `#${i}: ${f.title}${f.file ? ' [' + f.file + ']' : ''} - ${f.detail}`).join('\n')
  const panels = (await parallel(Array.from({ length: PANEL }, (_, c) => () =>
    agent(
      `You are adversarial CRITIC #${c + 1} for this EDT-MCP task:\n\n${task}\n\nHere are ${fresh.length} research findings (indexed):\n${numbered}\n\nVerify EACH against the real code/docs and return a verdict per index: CONFIRMED (you can point at the proof), REFUTED (wrong/unfounded), or REWORK (right direction, specifics need re-checking - say what). Default to REFUTED when uncertain. Return one verdict object per finding index.`,
      { phase: 'Critique', label: `critic:w${wave}#${c + 1}`, schema: BATCH_VERDICT_SCHEMA }))))
    .filter(Boolean)
    .map((r) => r.verdicts || [])

  // Aggregate: a finding is kept when a majority of the panel CONFIRMS it.
  const need = Math.ceil(PANEL / 2)
  fresh.forEach((f, i) => {
    let confirms = 0
    const reworks = []
    for (const verdicts of panels) {
      const v = verdicts.find((x) => x.index === i)
      if (!v) continue
      if (v.verdict === 'CONFIRMED') confirms++
      else if (v.verdict === 'REWORK') reworks.push(v.reason || 'recheck')
    }
    if (confirms >= need) confirmed.push(f)
    else if (reworks.length) reworkNotes += ` [${f.title}: ${reworks.join('; ')}]`
  })
  log(`Confirmed so far: ${confirmed.length}${reworkNotes ? ' (rework queued)' : ''}`)
  if (!reworkNotes) break // converged - nothing bounced back
}

// ---- Phase 4: architect synthesis -----------------------------------------------------------
phase('Architect')
const corpus = JSON.stringify({ docFindings, confirmed }, null, 0)
const spec = await agent(
  `You are the ARCHITECT for this EDT-MCP task:\n\n${task}\n\nConfirmed research findings and documentation facts (JSON):\n${corpus}\n\nProduce a concrete, Spec-Driven implementation plan:\n- a short summary and TESTABLE acceptance criteria;\n- the chosen approach;\n- a DEVELOPER PARTITION that splits the work into INDEPENDENT, FILE-DISJOINT slices so multiple developers can work in parallel without conflict - each slice with its files, the exact change, the invariant to preserve, and its tests;\n- recommendedDevs (how many of those slices can run in parallel).\n\nRespect the project MUST-ENFORCE rules (English-only code, unattended-safety, bilingual language-CODE keys, schema/execute parity + lowerCamelCase, reflective forms with no form-model import, transaction boundaries with state-flags only after commit, the unit + e2e + golden ratchets).\n\nIf a PRINCIPAL design question must be answered by a human BEFORE implementation (a wire-contract change, an architecture choice, bilingual semantics, a breaking change, a destructive operation, or an ambiguous/infeasible requirement), list it under escalations with 2-3 options - do NOT guess.`,
  { phase: 'Architect', schema: SPEC_SCHEMA, effort: 'high' })

return { spec, devPartition: spec.devPartition, escalations: spec.escalations || [] }
