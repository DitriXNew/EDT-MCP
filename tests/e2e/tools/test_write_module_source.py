"""
e2e tests for write_module_source (kind: write).

EXEMPLAR — write tool. Shows: happy path proven ON DISK via git diff, plus the
negative matrix (rejected write must not touch disk; missing required param;
stale searchReplace) with error-quality assertions.

Fixture target: CommonModules/OK/Module.bsl (empty in the committed baseline).
The orchestrator resets the fixture before every test, so each starts empty.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_diff_contains, assert_no_diff, e2e_test, PROJECT,
)

MODULE = "CommonModules/OK/Module.bsl"


@e2e_test(tool="write_module_source", kind="write")
def test_append_lands_on_disk():
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "append", "source": "// e2e_probe_append\n",
    })
    assert_ok(r, "append happy path")
    # On-disk truth: the new line must be in the .bsl file on disk (git sees it).
    assert_diff_contains("// e2e_probe_append", "append must persist to the .bsl on disk")


@e2e_test(tool="write_module_source", kind="write")
def test_missing_projectname_errors_clearly_and_no_write():
    r = call("write_module_source", {
        "modulePath": MODULE, "mode": "append", "source": "// x\n",
    })
    err = assert_error(r, "missing required projectName")
    assert_error_quality(err, names=["projectName"], ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="write_module_source", kind="write")
def test_searchreplace_stale_oldsource_errors_and_no_write():
    # Default-ish mode that requires oldSource; against an empty module the marker
    # cannot be found, so the write must be rejected and the disk left untouched.
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "searchReplace", "oldSource": "NOPE_NOT_PRESENT_XYZ", "source": "x",
    })
    err = assert_error(r, "searchReplace with absent oldSource")
    # Error must be clear and not a bare 'Error'/stack trace. (Whether it names the
    # exact oldSource value is an AUDIT point — tighten once the real message is seen.)
    assert_error_quality(err, ctx="stale oldSource produces a clear error")
    assert_no_diff("a rejected write must not touch the project on disk")
