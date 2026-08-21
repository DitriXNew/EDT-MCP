# Common operating rules

Apply these rules before every business-project skill:

- Use the current MCP schema/help and repository tool documentation as the
  authority for parameters, limits, side effects, identifiers, errors, and
  recovery. Do not invent undocumented behavior.
- On ambiguity, an unexpected state, unclear target or ownership, or a
  user-affecting/destructive action, stop and consult the authoritative help.
  Ask the user when safe continuation still needs permission or a decision.
- Keep the exact authorized project, object, application, repository, and file
  targets in scope. Discovery and read-only evidence never authorize mutation.
- Preserve returned job, launch, breakpoint, frame, hash, cursor, and preview
  identifiers; address only that retained operation and never rerun it merely
  to discover status.
- Report only tool-confirmed results, partial/truncated evidence, side effects,
  cleanup state, and anything that remains unproved.

If installed project `rules/` and a task skill appear to conflict, stop and
resolve the conflict instead of silently choosing the more permissive route.
