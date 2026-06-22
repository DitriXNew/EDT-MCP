export const meta = {
  name: 'edt-mcp-autopilot-discover',
  description: 'Research -> critics -> architect for an EDT-MCP task: produces an implementation spec, a file-disjoint developer partition, and any escalation questions for the human.',
  phases: [
    { title: 'Docs', detail: '2 documentation researchers' },
    { title: 'Code research', detail: 'size-scaled waves, loop-until-dry' },
    { title: 'Critique', detail: 'adversarial verify; refuted dropped, rework bounced back' },
    { title: 'Architect', detail: 'synthesise surviving findings into a spec + dev partition' },
  ],
}

// args:
//   task   - the task statement / issue text (string, required)
//   size   - 'small' | 'medium' | 'large' (scales fan-out; default 'medium')
//   dryRun - when true, agents only outline what they WOULD do (no deep work)
const task = (args && args.task) || 'No task provided.'
const size = (args && args.size) || 'medium'
const dryRun = !!(args && args.dryRun)

// Fan-out sizing by task size. budget.total (if the user set a "+Nk" directive) tightens caps.
const WAVE = { small: 3, medium: 5, large: 8 }[size] || 5
const MAX_WAVES = { small: 2, medium: 3, large: 4 }[size] || 3
const CRITICS = size === 'large' ? 3 : 2
const tight = budget && budget.total && budget.remaining() < 120000

const SKIP_BIAS =
  ' Report only what you verified against the real code/docs; be skeptical. ' +
  'No behaviour change is implied here - if something looks meaningful or risky, flag it, do not paper over it.'

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
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

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['CONFIRMED', 'REFUTED', 'REWORK'] },
    reason: { type: 'string' },
    correction: { type: 'string' },
  },
  required: ['verdict', 'reason'],
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
  `You are a DOCUMENTATION researcher for this EDT-MCP task:\n\n${task}\n\nStudy ONLY authoritative documentation (the local apidocs Javadoc index, the official EDT apidocs site, the project docs/ knowledge base, plugin docs). Report concrete API facts that bear on the task: exact class/method/enum names and signatures, constraints, version differences. Cite where each fact comes from.${dryRun ? ' DRY RUN: only outline what you would look up.' : ''}${SKIP_BIAS}`,
  `You are a PRECEDENT researcher for this EDT-MCP task:\n\n${task}\n\nFind how the project ALREADY does similar things: existing tools, shared helpers/resolvers, conventions, prior PRs/issues, and the relevant project skills. Report the reusable building blocks with their file paths so we reuse instead of reinventing.${dryRun ? ' DRY RUN: only outline.' : ''}${SKIP_BIAS}`,
]
const docFindings = (await parallel(docPrompts.map((p) => () =>
  agent(p, { phase: 'Docs', schema: FINDINGS_SCHEMA }))))
  .filter(Boolean)
  .flatMap((r) => r.findings)
log(`Docs: ${docFindings.length} findings`)

// ---- Phases 2-3: code-research waves + critics (loop-until-dry, critics can bounce back) -----
const ANGLES = [
  'data flow and exactly where the change must land',
  'existing patterns / shared helpers to reuse',
  'edge cases, error paths and unattended-safety',
  'tests and build ratchets that will gate the change',
  'cross-file callers and wire/contract impact',
  'bilingual (RU/EN) resolution paths',
  'transaction boundaries and state-flag timing',
  'forms / reflective model access (if relevant)',
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
      `You are CODE researcher #${i + 1} (wave ${wave}) for this EDT-MCP task:\n\n${task}\n\nInvestigate the PRODUCT CODE through this lens: ${lens}. Use ripgrep and read the real files. Report concrete, file-anchored findings (what is true, where, why it matters for the task).${reworkNotes ? ' Critics asked to re-examine: ' + reworkNotes : ''}${dryRun ? ' DRY RUN: only outline what you would inspect.' : ''}${SKIP_BIAS}`,
      { phase: 'Code research', label: `research:w${wave}#${i + 1}`, schema: FINDINGS_SCHEMA }))))
    .filter(Boolean)
    .flatMap((r) => r.findings)

  const fresh = found.filter((f) => !seen.has(keyOf(f)))
  fresh.forEach((f) => seen.add(keyOf(f)))
  log(`Wave ${wave}: ${found.length} found, ${fresh.length} new`)
  if (!fresh.length) break

  // Critique each fresh finding with independent skeptics.
  reworkNotes = ''
  const judged = (await parallel(fresh.map((f) => () =>
    parallel(Array.from({ length: CRITICS }, (_, c) => () =>
      agent(
        `You are an adversarial CRITIC #${c + 1} of a research finding on this EDT-MCP task:\n\n${task}\n\nFinding:\n- title: ${f.title}\n- file: ${f.file || '(none)'}\n- detail: ${f.detail}\n- evidence: ${f.evidence || '(none)'}\n\nVerify it against the REAL code/docs. Return CONFIRMED only if you can point at the proof; REFUTED if it is wrong or unfounded (quote what disproves it); REWORK if the direction is right but the specifics need re-investigation (say what to re-check). Default to REFUTED when uncertain.`,
        { phase: 'Critique', label: `critic:${(f.title || '').slice(0, 24)}`, schema: VERDICT_SCHEMA })))
      .then((vs) => ({ f, vs: vs.filter(Boolean) })))))
    .filter(Boolean)

  for (const { f, vs } of judged) {
    const confirms = vs.filter((v) => v.verdict === 'CONFIRMED').length
    const reworks = vs.filter((v) => v.verdict === 'REWORK')
    if (confirms >= Math.ceil(CRITICS / 2)) {
      confirmed.push(f)
    } else if (reworks.length) {
      reworkNotes += ` [${f.title}: ${reworks.map((r) => r.reason).join('; ')}]`
    }
  }
  log(`Confirmed so far: ${confirmed.length}${reworkNotes ? ' (rework queued)' : ''}`)
  if (!reworkNotes) break // nothing bounced back -> research has converged
}

// ---- Phase 4: architect synthesis -----------------------------------------------------------
phase('Architect')
const corpus = JSON.stringify({ docFindings, confirmed }, null, 0)
const spec = await agent(
  `You are the ARCHITECT for this EDT-MCP task:\n\n${task}\n\nConfirmed research findings and documentation facts (JSON):\n${corpus}\n\nProduce a concrete, Spec-Driven implementation plan:\n- a short summary and TESTABLE acceptance criteria;\n- the chosen approach;\n- a DEVELOPER PARTITION that splits the work into INDEPENDENT, FILE-DISJOINT slices so multiple developers can work in parallel without conflict - each slice with its files, the exact change, the invariant to preserve, and its tests;\n- recommendedDevs (how many of those slices can run in parallel).\n\nRespect the project MUST-ENFORCE rules (English-only code, unattended-safety, bilingual language-CODE keys, schema/execute parity + lowerCamelCase, reflective forms with no form-model import, transaction boundaries with state-flags only after commit, the unit + e2e + golden ratchets).\n\nIf a PRINCIPAL design question must be answered by a human BEFORE implementation (a wire-contract change, an architecture choice, bilingual semantics, a breaking change, a destructive operation, or an ambiguous requirement), list it under escalations with 2-3 options - do NOT guess.`,
  { phase: 'Architect', schema: SPEC_SCHEMA, effort: 'high' })

return { spec, devPartition: spec.devPartition, escalations: spec.escalations || [] }
