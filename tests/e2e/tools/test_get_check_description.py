"""
e2e tests for get_check_description (kind: read).

WHAT THE TOOL DOES
------------------
get_check_description expands an EDT *check ID* (e.g. the dash-cased code seen in
get_project_errors' "Check code" column, like "module-structure-method-in-regions")
into its human-readable documentation. It is NOT a model query: it reads a
`<checkId>.md` file from a folder configured in MCP preferences
(PreferenceConstants.PREF_CHECKS_FOLDER) and returns the file body verbatim.
Source: GetCheckDescriptionTool.getCheckDescription(checkId).

Parameters: `checkId` (string, required) and `projectName` (string, OPTIONAL). The
checkId may be the symbolic dash-cased id OR a short UID code (e.g. "SU23") as shown
by get_project_errors; when the direct `<checkId>.md` lookup misses AND projectName
is supplied, the id is resolved UID->symbolic via ICheckRepository.getUidForShortUid
(same mechanism as get_project_errors) and the lookup retried. projectName is read
ONLY for that resolution — the tool still never mutates TestConfiguration or the EDT
model, so EVERY test below ends with assert_no_diff() (a read tool that mutated the
project would be a bug, and this is the guardrail that catches it).

NOTE on validating the UID happy path: it requires BOTH a configured checks-docs
folder (off by default in the e2e workspace) AND a UID whose symbolic doc exists, so
the UID->doc resolution is a verify-in-EDT path (like the LanguageTool tools). The
pure UID->symbolic mapping is unit-tested with a mocked ICheckRepository
(GetCheckDescriptionToolTest.testResolveSymbolicCheckUid*). The test below proves the
new projectName param does not break the validation order or crash in the
unconfigured fixture.

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
  - folder pref unset/empty  -> "Check descriptions folder is not configured.\n\n
                                 Please set it in Preferences -> MCP Server."
  - folder does not exist     -> "Check descriptions folder does not exist: <folder>"
  - file not found/sanitized -> "Check description not found for: <checkId>"

WHY THERE IS NO assert_ok HAPPY PATH HERE (and why that is correct, not a gap)
-----------------------------------------------------------------------------
A *successful* (file-body) response requires an operator-configured external
preference folder containing a matching `<checkId>.md`. That folder is:
  - NOT part of the git fixture (no `*.md` exists under TestConfiguration/), and
  - OFF BY DEFAULT (DEFAULT_CHECKS_FOLDER == ""), and the e2e workspace leaves it
    unset.
So there is no fixture-independent, deterministic input that yields assert_ok.
Fabricating an assert_ok that only passes if some operator happened to point the
preference at a real docs folder would be FLAKY and would not reliably FAIL when
the tool is broken — exactly the anti-cheat trap (§6). Instead, the tool's correct,
deterministic baseline behaviour in the (unconfigured) fixture environment is a
*well-formed structured error*; the "happy" slot below asserts that baseline
response is well-formed and actionable (not a bare "Error"/blank/stacktrace) — a
real positive signal that the tool ran its validation/lookup pipeline end to end.

The negative matrix is otherwise minimal-by-nature: the only required param is
`checkId` and there is no enum/XOR/conditional parameter, so the reachable
client-input errors are "missing checkId" and "bad/unknown checkId".
"""

from harness import (
    call, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy path — the well-formed baseline response in the unconfigured fixture env.
#
# In the committed/e2e environment the check-descriptions folder is unset (OFF by
# default), so a valid-looking checkId resolves to the tool's deterministic
# baseline: a structured, actionable error. There is no fixture-independent
# assert_ok input (success needs an out-of-band preference folder that the fixture
# deliberately lacks — see the module docstring). We therefore assert the baseline
# is a GOOD, well-formed response rather than a bare/blank/garbled one.
#
# Mutation thinking: if the tool were broken (returned an empty body, a bare
# "Error", a raw stack trace, or silently "succeeded" with no validation), this
# would FAIL — the message must name a concrete remedy AND it must not mutate disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_check_description", kind="read")
def test_known_check_id_returns_wellformed_baseline_and_does_not_mutate():
    # A real, simple dash-cased check code (as emitted by get_project_errors). With
    # the docs folder unset (default) this deterministically hits one of two
    # baseline branches: "Check descriptions folder is not configured" (folder unset
    # -> directs the user to Preferences -> MCP Server) OR, if an operator DID
    # configure a folder, "Check description not found for: <checkId>". Either way
    # the response must be a well-formed, actionable error — never bare/blank.
    check_id = "module-structure-method-in-regions"
    r = call("get_check_description", {"checkId": check_id})

    # The tool ran its lookup pipeline and reported a structured failure (folder
    # unset / file absent) — both are the correct deterministic baseline here.
    err = assert_error(r, "valid-looking checkId in an unconfigured docs environment")

    # The baseline must be the actionable not-configured message in the default
    # (unset-folder) e2e workspace. This is the discriminating signal: a broken tool
    # that skipped the folder-config check, returned an empty body, or leaked a
    # stack trace would NOT contain this phrase. "Preferences" is the concrete
    # next-step the message points the operator at.
    #
    # NOTE: if the test environment HAS a configured folder, the baseline becomes
    # "Check description not found for: <checkId>" instead. Assert quality against
    # whichever applies so the test stays meaningful without being flaky.
    msg = err or ""
    if "not configured" in msg.lower() or "preferences" in msg.lower():
        assert_error_quality(
            err,
            names=[],  # the not-configured message is folder-state, not about checkId
            suggests=["Preferences"],
            ctx="unconfigured docs folder -> actionable 'set it in Preferences' remedy",
        )
    else:
        # Folder configured but this checkId has no .md -> must name the checkId and
        # be a clear not-found message (not a bare error).
        # AUDIT: the "not found" message names the checkId but offers no next step
        #   (no pointer to get_project_errors' "Check code" column to find a valid
        #   id, and no listing of available check docs). suggests=[] is deliberate;
        #   this is a fix-card to add an actionable hint.
        assert_contains(msg, "not found", "configured folder, missing doc -> 'not found'")
        assert_error_quality(
            err,
            names=[check_id],
            suggests=[],
            ctx="configured folder, unknown checkId -> names the bad checkId",
        )

    assert_no_diff("a read tool must not touch the project on disk")


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
    # Because the docs folder is unset by default, the deterministic baseline here is
    # the "not configured" branch (validated BEFORE the sanitizer runs). When a
    # folder IS configured, the sanitizer mismatch yields "Check description not
    # found for: <checkId>". Either way the result MUST be a structured error and
    # MUST NOT leak a file from outside the folder.
    evil = "../../../../etc/passwd"
    r = call("get_check_description", {"checkId": evil})
    err = assert_error(r, "path-traversal-shaped checkId")
    msg = (err or "")

    # Mutation guard: a broken sanitizer that resolved the traversal would return
    # foreign FILE CONTENT (success, no isError). assert_error already proves the
    # call failed; now prove it failed for a SAFE reason (a known error branch),
    # not by leaking an unrelated file body.
    low = msg.lower()
    assert ("not configured" in low) or ("not found" in low) or ("preferences" in low), \
        "traversal-shaped checkId must hit a known error branch, not resolve a file: %r" % msg
    # And it must NOT have returned the contents of a traversed file (e.g. /etc/passwd
    # starts with a "root:" line). assert_not_contains is the harness guardrail for
    # "this string must be absent" — here, no leaked file body.
    assert_not_contains(msg, "root:",
                        "path traversal must be blocked: no external file content may leak")

    # AUDIT: the not-configured / not-found messages do not explicitly state that the
    #   id was rejected for containing illegal characters; a client passing a dotted
    #   Xtext code (e.g. org.eclipse.xtext...Syntax) just sees a generic 'not found'.
    #   suggests=[] flags the missing actionable hint about the [a-zA-Z0-9_-] rule.
    assert_error_quality(
        err,
        names=[],  # the unset-folder baseline does not echo the (illegal) checkId
        suggests=[],
        ctx="traversal-shaped checkId rejected via a known error branch (no file leak)",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_check_description", kind="read")
def test_uid_shaped_checkid_with_project_name_does_not_crash_or_mutate():
    # A short UID-shaped checkId ("SU23") together with the optional projectName
    # exercises the NEW UID-resolution branch. In the e2e workspace the checks-docs
    # folder is unset, so resolution is never reached: the folder check fires first
    # and the deterministic baseline is "not configured" (or "not found" if an
    # operator configured a folder but this UID has no .md). The point of this test
    # is the GUARDRAIL: passing projectName must not crash, must not mutate the
    # project, and must still return a well-formed structured error — the actual
    # UID->doc resolution is a verify-in-EDT path (needs a configured docs folder).
    r = call("get_check_description", {"checkId": "SU23", "projectName": PROJECT})
    err = assert_error(r, "UID-shaped checkId with projectName, unconfigured folder")
    low = (err or "").lower()
    assert ("not configured" in low) or ("not found" in low) or ("preferences" in low), \
        "UID checkId must hit a known error branch in the unconfigured fixture: %r" % err
    assert_no_diff("supplying projectName must not let a read tool mutate the project")
