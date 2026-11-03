"""A linked configuration's BusinessProcess cannot be addressed through ExternalObjects."""

from harness import (
    EXT_OBJECTS_PROJECT, EXT_OBJECTS_REL, E2ESkip, assert_contains, assert_error,
    assert_no_diff_rel, assert_tree_unchanged, call, e2e_test, tree_snapshot,
)
from tools.test_business_process_route_map import TOOL, _get, _process


@e2e_test(tool=TOOL, kind="write-metadata")
def test_route_map_refuses_an_external_objects_project_for_every_action():
    with _process("ProjectScope") as fqn:
        assert _get(fqn)["routeMapExists"] is False
        before = tree_snapshot()
        for action in ("get", "replace", "remove"):
            params = {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn, "action": action}
            if action == "replace":
                params.update(points=[], connections=[])
            error = assert_error(call(TOOL, params), "refuse the wrong project before model access")
            if error.startswith("Project not found"):
                raise E2ESkip("the %s fixture is not loaded on this stand" % EXT_OBJECTS_PROJECT)
            assert error.startswith("'%s' cannot be addressed in project '%s'." %
                                    (fqn, EXT_OBJECTS_PROJECT)), error
            assert_contains(error, "Project '%s' is an EXTERNAL-OBJECTS project: it holds only "
                            "ExternalDataProcessor / ExternalReport objects" % EXT_OBJECTS_PROJECT)
            assert error.endswith(", never a BusinessProcess. Address that type in the configuration "
                                  "project it belongs to."), error
            assert "not found" not in error.lower(), error
        assert _get(fqn)["routeMapExists"] is False
        assert_tree_unchanged(before, "the wrong-project calls must not change the linked configuration")
        assert_no_diff_rel(EXT_OBJECTS_REL, "the external-objects project must not be written")
