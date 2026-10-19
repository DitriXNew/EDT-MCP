"""Regression guard for existing FQN case handling; not red-on-base evidence for #550."""

from harness import (
    call, assert_ok, assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


@e2e_test(tool="get_metadata_details", kind="read")
def test_wrong_case_programmatic_names_keep_existing_fqn_resolution():
    """Regression guard, not red-on-base evidence: bilingual FQN lookup already ignores case."""
    for fqn, expected in (("Catalog.cATALOG", "Catalog: Catalog"),
                          ("CommonModule.вычисление", "Вычисление")):
        result = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
        assert_ok(result, "FQN with a programmatic object name in another case")
        assert_contains(result.text, expected, "the canonical object name must be returned")
        assert_not_contains(result.text, "## Errors", "the FQN must resolve without a failure row")
    assert_no_diff("case-insensitive FQN reads must leave the fixture unchanged")
