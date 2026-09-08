"""
e2e tests for get_check_description (kind: read).

WHAT THE TOOL DOES
------------------
get_check_description expands an EDT *check ID* (e.g. the dash-cased code seen in
get_project_errors' "Check code" column, like "begin-transaction") into its
human-readable documentation. It is NOT a model query: it reads a `<checkId>.md`
file and returns the body verbatim. Since #31 those files SHIP WITH THE PLUGIN
(bundle resource `checks/`), so the tool answers on a fresh install with nothing
configured; PreferenceConstants.PREF_CHECKS_FOLDER is now an optional per-file
OVERRIDE, consulted first when set.
Source: GetCheckDescriptionTool.getCheckDescription(checkId) ->
CheckDescriptionLoader.load(checkId).

Parameters: `checkId` (string, required) and `projectName` (string, OPTIONAL). The
checkId may be the symbolic dash-cased id OR a short UID code (e.g. "SU23") as shown
by get_project_errors; when the direct `<checkId>.md` lookup misses AND projectName
is supplied, the id is resolved UID->symbolic via ICheckRepository.getUidForShortUid
(same mechanism as get_project_errors) and the lookup retried. projectName is read
ONLY for that resolution — the tool still never mutates TestConfiguration or the EDT
model, so EVERY test below ends with assert_no_diff() (a read tool that mutated the
project would be a bug, and this is the guardrail that catches it).

NOTE on validating the UID happy path: it needs a UID that BOTH resolves against the
fixture's check repository AND whose symbolic id has a shipped .md. Which UIDs a given
EDT build issues is not fixture-controlled, so the UID->doc resolution stays a
verify-in-EDT path. The pure UID->symbolic mapping is unit-tested with a mocked
ICheckRepository (GetCheckDescriptionToolTest.testResolveSymbolicCheckUid*). The test
below proves the projectName param does not break the validation order or crash.

RESPONSE / ERROR CONTRACT (verified against current source, 2026-06-03)
----------------------------------------------------------------------
getResponseType() is the default MARKDOWN. On success the file body is delivered
as a markdown EmbeddedResource (lands in r.text). EVERY failure path returns
`ToolResult.error(<msg>).toJson()` -> `{"success":false,"error":<msg>}`. The
protocol handler (McpProtocolHandler.isJsonErrorPayload + the MARKDOWN branch)
detects `success:false` and diverts it to a structured JSON response with
isError:true, so the failure is machine-detectable:
  - r.is_error == True
  - r.error_text() reads structured["error"] -> the exact message string.
(NOTE: an older testing-skill reference claimed these errors come back as
informational markdown with isError:false. That is STALE — the current source
routes them through ToolResult.error/isJsonErrorPayload, i.e. isError:true. The
source is authoritative; these tests follow it.)

Real execute() error messages (GetCheckDescriptionTool):
  - checkId null/empty       -> "checkId is required"
  - no description for id    -> "No check description for: <checkId>. Use the symbolic
                                 dash-cased id ..." (also the sanitizer-reject path:
                                 an id carrying anything outside [a-zA-Z0-9_-] never
                                 reaches a lookup)

THE HAPPY PATH IS NOW FIXTURE-INDEPENDENT (it was not, before #31)
------------------------------------------------------------------
The descriptions ship inside the plugin jar, so a shipped check id yields the file
body with nothing configured — the same on CI, on a fresh install and on a developer
stand. That is what makes the assert_ok below deterministic rather than flaky, and it
is exactly the behaviour #31 asked for: before it, this file could only assert that
the tool produced a well-formed "folder is not configured" error.

The negative matrix is otherwise minimal-by-nature: the only required param is
`checkId` and there is no enum/XOR/conditional parameter, so the reachable
client-input errors are "missing checkId" and "bad/unknown checkId".
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy path — a check the plugin SHIPS a description for resolves with nothing
# configured (#31). This is the assert_ok this file could not have before: the
# .md files now live in the plugin jar, so the same input succeeds on CI, on a
# fresh install and on a developer stand.
#
# Mutation thinking: a broken lookup (wrong resource path, a checks/ folder left
# out of build.properties, a sanitizer that rejects a legal id, or a tool that
# still demanded the preference) FAILS here — the call would come back isError.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_check_description", kind="read")
def test_shipped_check_id_returns_its_description_and_does_not_mutate():
    # A real dash-cased check code that the plugin ships a description for.
    check_id = "begin-transaction"
    r = call("get_check_description", {"checkId": check_id})
    assert_ok(r, "a shipped check id must resolve with no folder configured")

    body = r.text or ""
    # Not merely non-empty: it must be THIS check's document. A tool that returned
    # some other file (a wrong-resource bug the mere presence of text would hide)
    # would not carry the check's own name.
    assert len(body.strip()) > 100, (
        "expected a real description body, got %r" % body[:200])
    assert_contains(body.lower(), "transaction",
                    "the body must be the begin-transaction document")
    # And it must be the Markdown file verbatim, not a JSON envelope around it.
    assert_not_contains(body, '"success"',
                        "the body is returned as-is, not wrapped in a result envelope")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_check_description", kind="read")
def test_unknown_check_id_errors_and_names_the_id():
    # The other side of the same lookup: an id the plugin ships nothing for must be
    # a structured error that NAMES the id, not an empty body or a bare "Error". The
    # id is synthetic so no future addition to checks/ can turn this green-by-accident.
    check_id = "no-such-check-e2e-xyz"
    r = call("get_check_description", {"checkId": check_id})
    err = assert_error(r, "an id with no shipped description")
    assert_error_quality(
        err,
        names=[check_id],
        suggests=["symbolic", "get_project_errors"],
        ctx="unknown checkId names the bad value and says where a good one comes from",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (mandatory)
#
# The tool has a single required param (`checkId`) and no enum/XOR/conditional
# parameters, so the reachable client-input errors are: missing checkId, empty
# checkId, and a path-traversal-shaped checkId. Each asserts error QUALITY, not
# just the fact of failure, and the read guardrail (assert_no_diff).
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_check_description", kind="read")
def test_missing_checkid_errors_clearly():
    # Required param omitted entirely. getCheckDescription() validates checkId FIRST
    # (before any folder/preferences logic), so this is deterministic regardless of
    # whether a docs folder is configured: ToolResult.error("checkId is required").
    r = call("get_check_description", {})
    err = assert_error(r, "missing required checkId")
    # The message must name the missing parameter so a client knows WHAT to supply.
    # AUDIT: it names `checkId` but offers no next step (e.g. "run get_project_errors
    #   and pass a value from the 'Check code' column"). suggests=[] is deliberate —
    #   a fix-card to make the message actionable, not a weakened assertion.
    assert_error_quality(
        err,
        names=["checkId"],
        suggests=[],
        ctx="missing checkId names the required parameter",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_check_description", kind="read")
def test_empty_checkid_errors_clearly():
    # Empty-string checkId hits the same `checkId == null || isEmpty()` guard as the
    # missing case (getCheckDescription line: "checkId is required"). Distinct from
    # the omitted case at the wire level (the key IS present, but blank) — both must
    # be rejected with the same clear message; a broken validator that only guarded
    # null (not empty) would fall through to a confusing folder/lookup error instead.
    r = call("get_check_description", {"checkId": ""})
    err = assert_error(r, "empty checkId")
    assert_error_quality(
        err,
        names=["checkId"],
        suggests=[],  # AUDIT (same as above): no actionable next step in the message.
        ctx="empty checkId is rejected with the same 'checkId is required' message",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_check_description", kind="read")
def test_path_traversal_shaped_checkid_is_rejected_not_resolved():
    # checkId is sanitized against path traversal: anything outside [a-zA-Z0-9_-] is
    # stripped, and if the sanitized id != input the lookup treats it as not found
    # (findCheckDocumentationFile returns null). A traversal-shaped id must therefore
    # NEVER resolve to a file outside the docs folder; it must come back as an error.
    #
    # Since #31 the descriptions are bundle resources and there is no preference to
    # be unset, so the sanitizer IS the branch this reaches: the id differs from its
    # sanitized form, the lookup is refused, and the result is the "no description
    # for" error. It MUST NOT be a file from outside checks/.
    evil = "../../../../etc/passwd"
    r = call("get_check_description", {"checkId": evil})
    err = assert_error(r, "path-traversal-shaped checkId")
    msg = (err or "")

    # Mutation guard: a broken sanitizer that resolved the traversal would return
    # foreign FILE CONTENT (success, no isError). assert_error already proves the
    # call failed; now prove it failed for a SAFE reason (the known refusal branch),
    # not by leaking an unrelated file body.
    low = msg.lower()
    assert "no check description for" in low, \
        "traversal-shaped checkId must hit the refusal branch, not resolve a file: %r" % msg
    # And it must NOT have returned the contents of a traversed file (e.g. /etc/passwd
    # starts with a "root:" line). assert_not_contains is the harness guardrail for
    # "this string must be absent" — here, no leaked file body.
    assert_not_contains(msg, "root:",
                        "path traversal must be blocked: no external file content may leak")

    # AUDIT: the refusal message does not state that the id was rejected for carrying
    #   illegal characters; a client passing a dotted Xtext code (e.g.
    #   org.eclipse.xtext...Syntax) sees the generic "no description" wording instead
    #   of the [a-zA-Z0-9_-] rule. suggests names what the message DOES offer.
    assert_error_quality(
        err,
        names=[evil],
        suggests=["symbolic"],
        ctx="traversal-shaped checkId rejected via the refusal branch (no file leak)",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_check_description", kind="read")
def test_uid_shaped_checkid_with_project_name_does_not_crash_or_mutate():
    # A short UID-shaped checkId ("SU23") together with the optional projectName
    # exercises the UID-resolution branch. Whether THIS UID resolves to a check with a
    # shipped description depends on the EDT build's check registry, which the fixture
    # does not control - so the test asserts the GUARDRAIL either way: passing
    # projectName must not crash and must not mutate the project, and when the id does
    # not resolve the answer must be the well-formed refusal, never a bare error.
    r = call("get_check_description", {"checkId": "SU23", "projectName": PROJECT})
    if r.is_error:
        low = (r.error_text() or "").lower()
        assert "no check description for" in low, \
            "an unresolved UID must hit the refusal branch: %r" % r.error_text()
    else:
        assert len((r.text or "").strip()) > 100, \
            "a resolved UID must return a real description body, got %r" % (r.text or "")[:200]
    assert_no_diff("supplying projectName must not let a read tool mutate the project")
