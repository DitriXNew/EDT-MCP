"""Measure the localized stand registry's default/maximum project_checks text pages."""

from harness import PROJECT, assert_no_diff, assert_ok, call, e2e_test
from tools.test_project_checks import _snapshot


@e2e_test(tool="project_checks", kind="action")
def test_default_and_maximum_pages_fit_below_the_text_guard():
    before = _snapshot()
    measurements = []
    for limit, expected in ((None, 50), (1000, 100)):
        args = dict(projectName=PROJECT, action="list")
        if limit is not None:
            args["limit"] = limit
        result = call("project_checks", args)
        assert_ok(result, "measure localized project_checks page")
        page = result.structured
        assert isinstance(page, dict), page
        assert page["returned"] == min(page["total"], expected), page
        assert len(page["checks"]) == page["returned"], page
        assert "[OUTPUT TRUNCATED]" not in result.text, result.text[-500:]
        chars = len(result.text)
        assert chars < (50000 if limit is None else 80000), chars
        measurements.append((limit, page["returned"], chars))
    print("project_checks stand page measurements (limit, returned, chars):", measurements)
    assert _snapshot() == before
    assert_no_diff()
