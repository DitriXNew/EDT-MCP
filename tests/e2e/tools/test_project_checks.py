"""project_checks (kind: action), run on the stand with consent=allow.

These checks must first fail against the master jar (Unknown tool) and then pass
against this branch. Regenerate golden/docs only after that pin.

.settings/*.cset and the check .prefs are git-ignored: the harness reset and
assert_no_diff cannot see them. Every write below restores its own response.before
in finally, verifies the restored repository values, and reads .cset/.prefs
directly. Restoring defaults can leave an empty Default.cset, which EDT retains.
The profile test deletes only the file it created. No clean_project/restart is
used, so marker tests exercise the live CheckScheduler listener.

Consent denial is covered by the Java seam: the harness cannot change the EDT
process's EDT_MCP_DESTRUCTIVE_CONSENT per call. Profile creation/management and
parameter writes are follow-ups. get_project_errors displays symbolic Check code
when available and has no severity column: we test severity via its severity
filter, and resolve its discovered id through project_checks for a shortUid.
"""

import json
import time
from contextlib import contextmanager
from pathlib import Path

from harness import (
    PROJECT, PROJECT_DIR, E2ESkip, call, assert_ok, assert_error,
    assert_error_quality, assert_no_diff, e2e_test, split_markdown_row,
)


SETTINGS = Path(PROJECT_DIR) / ".settings"
PREFS = SETTINGS / "com.e1c.g5.v8.dt.check.prefs"
SEVERITIES = ("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL")


def _success(action, **params):
    result = call("project_checks", dict(projectName=PROJECT, action=action, **params))
    assert_ok(result, "project_checks " + action)
    data = result.structured
    assert isinstance(data, dict) and data.get("success") is True, data
    return data


def _snapshot(with_times=False):
    """Ignored files are part of our ground truth, independently of git status."""
    paths = list(SETTINGS.glob("*.cset")) + ([PREFS] if PREFS.exists() else [])
    return {path.name: (path.read_bytes(), path.stat().st_mtime_ns) if with_times else path.read_bytes()
            for path in paths}


def _stored(profile):
    path = SETTINGS / (profile + ".cset")
    if not path.exists():
        return {}
    document = json.loads(path.read_text(encoding="utf-8-sig"))
    assert document["version"] == 1, document
    assert isinstance(document["settings"], dict), document
    return document["settings"]


def _active_on_disk():
    if not PREFS.exists():
        return "Default"
    entries = dict(line.split("=", 1) for line in PREFS.read_text(encoding="utf-8-sig").splitlines()
                   if "=" in line and not line.startswith("#"))
    return entries.get("activeProfile", "Default")


def _poll(probe, predicate, context, timeout=90):
    """Bounded condition polling; no fixed delay used as proof of completion."""
    deadline = time.monotonic() + timeout
    while True:
        observed = probe()
        if predicate(observed):
            return observed
        if time.monotonic() >= deadline:
            raise AssertionError("%s: last observed %r" % (context, observed))
        time.sleep(0.5)


def _one(check_id):
    data = _success("list", checkIds=[check_id])
    assert data["total"] == data["returned"] == 1, data
    return data["checks"][0]


def _all():
    rows = []
    offset = 0
    while True:
        data = _success("list", limit=200, offset=offset)
        assert data["offset"] == offset, data
        assert data["returned"] == len(data["checks"]), data
        rows.extend(data["checks"])
        offset += data["returned"]
        if offset >= data["total"]:
            return rows
        assert data["returned"] > 0, data


def _set(changes):
    return _success("set", changes=json.dumps(changes))


@contextmanager
def _temporary_set(row, check_id=None, **values):
    """Restoration remains required even if an assertion in the mutation body fails."""
    active = _success("profiles")["activeProfile"]
    original = _one(row["uid"])
    stored = _stored(active)
    prefs = PREFS.read_bytes() if PREFS.exists() else None
    response = None
    try:
        response = _set([dict(checkId=check_id or row["uid"], **values)])
        assert response["profile"] == active, response
        assert response["persisted"] is True, response
        assert response["before"][0]["uid"] == row["uid"], response
        for name in ("enabled", "severity"):
            assert response["before"][0][name] == original[name], response
        for name, expected in values.items():
            assert response["after"][0][name] == expected, response
        yield response
    finally:
        before = response["before"][0] if response is not None else original
        restore = _set([dict(checkId=before["uid"], enabled=before["enabled"], severity=before["severity"])])
        assert restore["persisted"] is True, restore
        actual = _one(before["uid"])
        assert (actual["enabled"], actual["severity"]) == (before["enabled"], before["severity"]), actual
        _poll(lambda: _stored(active), lambda settings: settings == stored, "restore .cset deltas")
        assert (PREFS.read_bytes() if PREFS.exists() else None) == prefs, "set changed active-profile prefs"
        assert _active_on_disk() == active
        assert_no_diff()


def _error_rows(check_id=None, severity=None):
    args = dict(projectName=PROJECT, limit=1000)
    if check_id:
        args["checkId"] = check_id
    if severity:
        args["severity"] = severity
    result = call("get_project_errors", args)
    assert_ok(result, "get_project_errors live check markers")
    text = result.text
    if isinstance(result.structured, dict) and "report" in result.structured:
        text = result.structured["report"]
    rows = []
    for line in text.splitlines():
        cells = split_markdown_row(line)
        if len(cells) >= 6 and cells[0] != "Description" and set("".join(cells)) - set("-: "):
            code = cells[4].strip("`")
            if code and (check_id is None or code == check_id):
                rows.append(code)
    return rows


def _cheap_noncritical_check(checks, require_default=False, codes=None):
    # These documented syntax checks need no cross-project data. Prefer begin-transaction
    # when enabled; all three have NORMAL complexity in the shipped check guides.
    preferred = ("begin-transaction", "empty-except-statement", "export-procedure-missing-comment")
    for check_id in preferred:
        candidates = sorted((row for row in checks
                             if row["checkId"] == check_id and row["enabled"]
                             and not row["criticalDataIntegrity"] and row["hasDocumentation"]
                             and (not require_default or (row["defaultEnabled"] and not row["customized"]))
                             and (codes is None or row["checkId"] in codes or row["shortUid"] in codes)),
                            key=lambda row: row["uid"])
        if candidates:
            return candidates[0]
    raise E2ESkip("fixture has no eligible enabled non-critical documented syntax check: "
                  + ", ".join(preferred))


def _check_with_markers():
    # Check that our tool exists before any environment-dependent skip (master must be RED).
    checks = _all()
    codes = _error_rows()
    if not codes:
        raise E2ESkip("fixture has no validation markers; live marker transition needs at least one")
    return _cheap_noncritical_check(checks, codes=codes)


@e2e_test(tool="project_checks", kind="action")
def test_list_returns_the_check_tree_with_ids_severities_and_the_active_profile():
    before = _snapshot()
    checks = _all()
    assert len(checks) > 50, len(checks)
    assert _success("profiles")["activeProfile"] == "Default"
    required = {"checkId", "contributorId", "uid", "shortUid", "enabled", "severity", "type",
                "title", "category", "defaultEnabled", "defaultSeverity", "customized",
                "criticalDataIntegrity", "hasDocumentation"}
    for row in checks:
        assert required <= row.keys(), row
        assert row["uid"] == row["checkId"] + "|" + row["contributorId"], row
        assert row["severity"] in SEVERITIES, row
        assert isinstance(row["enabled"], bool), row
        assert "parameters" not in row, row
    assert checks == sorted(checks, key=lambda row: (row["category"], row["checkId"], row["uid"]))
    assert any(row["checkId"] == "begin-transaction" and row["hasDocumentation"] for row in checks)
    assert _snapshot() == before
    assert_no_diff()


@e2e_test(tool="project_checks", kind="action")
def test_list_with_check_ids_returns_only_those_and_their_parameters():
    before = _snapshot()
    row = _one("begin-transaction")
    assert row["checkId"] == "begin-transaction", row
    assert isinstance(row["parameters"], list), row
    for parameter in row["parameters"]:
        assert {"name", "value", "isDefault", "type"} <= parameter.keys(), parameter
    assert _one(row["shortUid"])["uid"] == row["uid"]
    assert _snapshot() == before
    assert_no_diff()


@e2e_test(tool="project_checks", kind="action")
def test_profiles_lists_default_as_active_on_the_pristine_fixture():
    before = _snapshot()
    profiles = _success("profiles")
    assert profiles["activeProfile"] == "Default", profiles
    assert any(row["name"] == "Default" and row["active"] is True for row in profiles["profiles"]), profiles
    assert all(isinstance(row["customizedChecks"], int) for row in profiles["profiles"]), profiles
    assert _active_on_disk() == "Default"
    assert _snapshot() == before
    assert_no_diff()


@e2e_test(tool="project_checks", kind="action")
def test_set_disable_then_restore_round_trips_through_before_and_writes_the_cset():
    row = _cheap_noncritical_check(_all(), require_default=True)
    active = _success("profiles")["activeProfile"]
    assert row["uid"] not in _stored(active)
    with _temporary_set(row, enabled=False) as response:
        assert response["changed"] == 1, response
        _poll(lambda: _stored(active), lambda settings: settings.get(row["uid"], {}).get("enabled") is False,
              "disabled check persisted in .cset")
        customized = _success("list", checkIds=[row["uid"]], onlyCustomized=True)
        assert customized["total"] == 1 and customized["checks"][0]["uid"] == row["uid"], customized
        assert not row["criticalDataIntegrity"], row
    assert row["uid"] not in _stored(active), "restored default must have no stored delta"
    assert _success("list", checkIds=[row["uid"]], onlyCustomized=True)["total"] == 0


@e2e_test(tool="project_checks", kind="action")
def test_set_severity_changes_live_markers():
    row = _check_with_markers()
    code = row["checkId"]
    old = row["severity"]
    new = "MINOR" if old != "MINOR" else "MAJOR"
    assert _error_rows(code, old), "discover a marker at the original severity"
    active = _success("profiles")["activeProfile"]
    with _temporary_set(row, severity=new):
        _poll(lambda: _stored(active),
              lambda settings: settings.get(row["uid"], {}).get("severity", row["defaultSeverity"]) == new,
              "severity persisted in .cset")
        _poll(lambda: (_error_rows(code, new), _error_rows(code, old)),
              lambda rows: bool(rows[0]) and not rows[1], "markers retagged live")
    _poll(lambda: (_error_rows(code, old), _error_rows(code, new)),
          lambda rows: bool(rows[0]) and not rows[1], "marker severity restored")


@e2e_test(tool="project_checks", kind="action")
def test_set_disable_removes_the_checks_markers_without_clean_project():
    row = _check_with_markers()
    active = _success("profiles")["activeProfile"]
    with _temporary_set(row, enabled=False):
        assert _stored(active).get(row["uid"], {}).get("enabled", row["defaultEnabled"]) is False
        _poll(lambda: _error_rows(row["checkId"]), lambda rows: not rows, "disabled check markers removed")
    _poll(lambda: _error_rows(row["checkId"]), bool, "restored check markers re-created")


@e2e_test(tool="project_checks", kind="action")
def test_set_with_no_effective_change_reports_changed_zero_and_touches_nothing():
    row = _one("begin-transaction")
    before = _snapshot(with_times=True)
    with _temporary_set(row, enabled=row["enabled"], severity=row["severity"]) as response:
        assert response["changed"] == 0, response
        assert response["before"] == response["after"], response
        assert _snapshot(with_times=True) == before
    assert _snapshot(with_times=True) == before


@e2e_test(tool="project_checks", kind="action")
def test_set_refuses_an_unknown_check_id_naming_list_and_an_unknown_severity_naming_the_five():
    before = _snapshot()
    result = call("project_checks", dict(projectName=PROJECT, action="set",
                  changes=json.dumps([dict(checkId="e2e-absent-check-757", enabled=False)])))
    error = assert_error(result)
    assert_error_quality(error, names=["e2e-absent-check-757"], suggests=["project_checks list"])
    result = call("project_checks", dict(projectName=PROJECT, action="set",
                  changes=json.dumps([dict(checkId="begin-transaction", severity="SEVERE")])))
    error = assert_error(result)
    assert_error_quality(error, names=["SEVERE"], suggests=list(SEVERITIES))
    for name in SEVERITIES:
        assert name in error, error
    assert _snapshot() == before
    assert_no_diff()


@e2e_test(tool="project_checks", kind="action")
def test_set_accepts_the_short_uid_from_get_project_errors():
    row = _check_with_markers()
    active = _success("profiles")["activeProfile"]
    with _temporary_set(row, check_id=row["shortUid"], enabled=False) as response:
        assert response["before"][0]["uid"] == row["uid"], response
        assert _stored(active).get(row["uid"], {}).get("enabled", row["defaultEnabled"]) is False


@e2e_test(tool="project_checks", kind="action")
def test_switch_to_a_missing_profile_is_refused_and_creates_no_cset():
    missing = "E2eMissing757"
    before = _snapshot()
    assert not (SETTINGS / (missing + ".cset")).exists()
    error = assert_error(call("project_checks", dict(projectName=PROJECT, action="switch", profile=missing)))
    assert_error_quality(error, names=[missing], suggests=["project_checks profiles"])
    assert not (SETTINGS / (missing + ".cset")).exists()
    assert _snapshot() == before
    assert_no_diff()


@e2e_test(tool="project_checks", kind="action")
def test_switch_round_trip_between_default_and_a_profile_created_on_disk():
    name = "E2eProfile"
    path = SETTINGS / (name + ".cset")
    original = _success("profiles")["activeProfile"]
    assert original == "Default", original
    original_settings = _stored(original)
    assert not path.exists(), "test owns only a newly created E2eProfile.cset"
    response = None
    try:
        path.write_text('{"version":1,"settings":{}}', encoding="utf-8")
        _poll(lambda: _success("profiles"), lambda result: any(row["name"] == name for row in result["profiles"]),
              "repository discovers on-disk profile")
        response = _success("switch", profile=name)
        assert response["before"]["activeProfile"] == original, response
        assert response["after"]["activeProfile"] == name and response["persisted"] is True, response
        assert response["profile"] == name, response
        _poll(_active_on_disk, lambda active: active == name, "async activeProfile preference flush")
        assert json.loads(path.read_text(encoding="utf-8-sig"))["settings"] == {}
    finally:
        previous = response["before"]["activeProfile"] if response is not None else original
        restored = _success("switch", profile=previous)
        assert restored.get("persisted", restored["changed"] is False) is True, restored
        assert _success("profiles")["activeProfile"] == previous
        assert _stored(previous) == original_settings, "a pure switch must not overlay target settings"
        _poll(_active_on_disk, lambda active: active == previous, "original activeProfile preference restored")
        assert "activeProfile=" not in (PREFS.read_text(encoding="utf-8-sig") if PREFS.exists() else "")
        if path.exists():
            path.unlink()
        assert not path.exists()
        assert_no_diff()


@e2e_test(tool="project_checks", kind="action")
def test_required_parameters_unknown_actions_and_malformed_batches_are_actionable():
    before = _snapshot()
    for params, names, suggests in [
        ({"action": "list"}, ["projectName"], ["required"]),
        ({"projectName": PROJECT}, ["action"], ["required"]),
        ({"projectName": PROJECT, "action": "erase"}, ["erase"], ["list", "set", "profiles", "switch"]),
        ({"projectName": PROJECT, "action": "set"}, ["changes"], ["non-empty", "get_tool_guide"]),
        ({"projectName": PROJECT, "action": "set", "changes": "[]"}, ["changes"], ["non-empty"]),
        ({"projectName": PROJECT, "action": "set", "changes": '[{"checkId":"begin-transaction"}]'},
         ["begin-transaction"], ["enabled", "severity"]),
        ({"projectName": PROJECT, "action": "switch"}, ["profile"], ["project_checks profiles"]),
        ({"projectName": "E2eAbsentProject757", "action": "list"}, ["E2eAbsentProject757"], ["list_projects"]),
    ]:
        assert_error_quality(assert_error(call("project_checks", params)), names=names, suggests=suggests)
    assert _snapshot() == before
    assert_no_diff()
