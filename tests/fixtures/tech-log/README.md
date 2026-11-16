# Technological-log fixtures

`real-8.5.1-createinfobase.log` is the byte-exact 4,781-byte JSON capture supplied for issue #470, produced by platform 8.5.1 on Windows. It starts with UTF-8 BOM; every value is a string. LIC uses `txt`, HASP uses `Txt`; newlines are JSON-escaped.

`synthetic.log` is SYNTHETIC diagnostic evidence, using that exact JSON-lines shape and documented platform property spellings. It adds errors, SQL, server calls, managed locks, DBMS lock annotations and process lifecycle. It is not a real stand capture and proves no platform emission or performance claim. Synthetic PID/session/connection values exist only to exercise scoped correlation; source directories provide inferred node boundaries.

Tests create corrupt/tail/zip variants in temporary directories and remove them afterwards. No text-format parser or converted legacy log is provided.
