---
name: edt-mcp-project-query-dcs
description: Locate, inspect, validate, and safely change 1C queries or Data Composition Schemas through EDT-MCP. Separates supported schema writes from runtime data proof.
---

# EDT-MCP query and DCS workflow

## Goal

Preserve the query's business grain and use only the DCS mutations supported by
the current structured tool surface.

## Use when

- locating or changing a query in BSL;
- inspecting a report or DCS template;
- validating query syntax and metadata resolution;
- authoring supported DCS datasets, fields, or parameters;
- diagnosing duplicate or missing report output.

## Do not use when

- the task is only form layout around a report;
- the requested DCS setting is absent from the current writer;
- runtime data execution would require an unrelated external server.

## Query workflow

1. Locate the owner with `search_in_code`, module structure, or metadata
   details.
2. Read the complete method or DCS dataset that owns the query.
3. Extract the complete query text, including package-query stages.
4. Call `validate_query` with the exact project; use DCS mode for a DCS query.
5. Treat incomplete model resolution or fallback parsing as insufficient for a
   metadata-sensitive conclusion.
6. Apply the smallest supported source or DCS mutation.
7. Validate the final query again and re-read the owning artifact.

## DCS read and write boundary

`get_metadata_details` on a DCS template can expose data sources, datasets,
full query text, fields, calculated and total fields, parameters, and parts of
the default settings variant.

Current structured DCS writes use the `modify_metadata` DCS payload on a Report
FQN. Confirm the current guide before writing. Do not infer that every readable
setting is writable. If grouping structure, resource placement, selected-field
layout, variants, advanced filters/order, conditional appearance, or another
requested setting is absent from the guide, report a capability gap.

Never edit `.dcs` directly to bypass that gap.

## Query design checks

- State the dataset business grain.
- Aggregate one-to-many sources to the required key before joining.
- Do not use `DISTINCT` to hide unknown cardinality.
- Treat the final SELECT of a package query as the dataset output.
- Preserve access restrictions and required volume predicates.
- Validate field aliases against DCS fields and visible consumers.

## Verification

After a write, re-read the DCS or method, compare intended schema sections,
run targeted project validation, and execute the report only when visible data
semantics are part of acceptance.

Parser/model validation proves syntax and project metadata compatibility. It
does not prove rows, totals, RLS, performance, parameter behavior, or runtime
presentation.

## Stop conditions

Stop when query ownership is ambiguous, validation cannot resolve the current
model, the writer lacks the requested DCS capability, or runtime data proof is
required but the target infobase and read authority are not established.
