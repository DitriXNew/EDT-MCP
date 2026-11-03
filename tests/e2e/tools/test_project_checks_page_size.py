"""Measure full localized project_checks text pages on the guarded delivery path."""

import json

from harness import PROJECT, assert_no_diff, assert_ok, call, e2e_test, initialize
from tools.test_project_checks import _snapshot


@e2e_test(tool="project_checks", kind="action")
def test_default_and_maximum_pages_fit_below_the_text_guard():
    before = _snapshot()
    measurements = []
    try:
        # JSON's normal text is only a bounded success digest. Opt out of structured
        # content so the protocol sends the full JSON through OutputSizeGuard instead.
        initialize({"experimental": {"structuredContent": False}})
        for limit, expected in ((None, 50), (1000, 100)):
            args = dict(projectName=PROJECT, action="list")
            if limit is not None:
                args["limit"] = limit
            result = call("project_checks", args)
            assert_ok(result, "measure localized project_checks full text page")
            assert result.structured is None, "full text measurement requires structuredContent opt-out"
            text = result.text
            assert "[OUTPUT TRUNCATED]" not in text, "full text page was truncated: " + text[-500:]
            # Java String.length() counts UTF-16 code units, including surrogate pairs.
            chars = len(text.encode("utf-16-le")) // 2
            assert chars < (50000 if limit is None else 80000), ("full text page near guard", limit, chars)
            page = json.loads(text)
            assert isinstance(page, dict), page
            assert page["returned"] == min(page["total"], expected), page
            assert len(page["checks"]) == page["returned"], page
            measurements.append((limit, page["returned"], chars))
    finally:
        # Restore the runner's standard structured-content session for following tests.
        initialize()
    print("project_checks stand page measurements (limit, returned, UTF-16 chars):", measurements)
    assert _snapshot() == before
    assert_no_diff()
