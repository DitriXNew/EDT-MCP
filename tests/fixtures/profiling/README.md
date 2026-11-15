# Persisted EDT profiling fixtures

Two real YAXUnit measurements over TestConfiguration.tests, recorded on 2026-06-06:

- 60f80e45-8156-4b11-bad5-9207611a9033 (08:25)
- 71da0ce9-2993-4969-9b55-5cfa35d770f4 (10:28)

The measurement folders are native EDT exports (results_info.xml and modules_info.properties). Only each infobase_connection property was sanitized to File="C:\infobases\TestConfiguration"; using Java properties escaping. XML, source text, timings, dates and UUIDs are unchanged.

E2E imports this directory, addresses the fixed UUIDs, and verifies re-import is skipped. These two measurements remain in the workspace store; tests never clear other measurements or assume an empty store. Exports use temporary directories outside the repository and are removed on exit. No infobase connection or live profiling run is needed.
