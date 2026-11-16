# Technological-log investigations

Use `get_tech_log` for a sourced event/property/profile/problem reference and
problems-first analysis of absolute JSON log paths, including rotated `.log.zip`.
Summary groups lead to paged details and interval/identity chains; coverage
explains absent events without treating them as evidence of health. The parser
accepts BOM, string-valued properties and case variants; record durations display
in ms. Bounded in-memory indices retain file/byte pointers, not a database.

Use `manage_tech_log` preview to inspect errors, slow-calls, db, locks, memory or
scenario profiles. Start/stop/export use the existing destructive-consent gate and
refuse read-only mode. Capture defaults to a fresh user-temp directory, JSON/plain
placement, one-hour retention and 100 MB file rotation. User-level logcfg.xml is
backed up and restored byte-exactly; foreign edits refuse automatic replacement.
Status reports config writes separately from observed file pickup (up to 60 s).
Service-account clusters require their own account configuration and all-node
collection. Stop immediately after a short reproduction, then follow summary →
details/chain → fix → re-measure. Full reference:
[get_tech_log](../tools/get_tech_log.md) and
[manage_tech_log](../tools/manage_tech_log.md).
