"""Offline coverage for parsed .mdo ordering diagnostics."""

import importlib.util
from pathlib import Path
import sys
from types import ModuleType
import unittest
from unittest import mock


E2E = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("sort_order_harness", E2E / "harness.py")
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class SortMetadataDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        source = E2E / "tools/test_sort_metadata.py"
        self.module = ModuleType("sort_metadata_under_test")
        self.module.__file__ = str(source)
        with mock.patch.dict(sys.modules, {"harness": HARNESS}), mock.patch.object(HARNESS, "REGISTRY", []):
            exec(compile(source.read_bytes(), str(source), "exec"), self.module.__dict__)
        self.xml = ("<Configuration><padding>" + "x" * 800 + "</padding>"
                    "<catalogs>Catalog.Beta</catalogs><catalogs>Catalog.Alpha</catalogs></Configuration>")

    def test_failure_names_the_last_parsed_order_beyond_the_truncated_xml_dump(self):
        def failed_poll(*args, **kwargs):
            self.assertFalse(kwargs["check"](self.xml))
            raise HARNESS.E2EAssertion("content check failed; " + self.xml[:700])

        with mock.patch.object(self.module, "poll_disk_contains_all", side_effect=failed_poll):
            with self.assertRaises(HARNESS.E2EAssertion) as failure:
                self.module._disk_order("Configuration.mdo", "catalogs", ["Alpha", "Beta"])
        message = str(failure.exception)
        self.assertIn("expected ['Alpha', 'Beta']", message)
        self.assertIn("actual parsed order ['Beta', 'Alpha']", message)

    def test_success_preserves_the_shared_poll_result_and_fixture_scope(self):
        def successful_poll(*args, **kwargs):
            self.assertTrue(kwargs["check"](self.xml))
            self.assertEqual("tests/tests", kwargs["fixture_rel"])
            return self.xml

        with mock.patch.object(self.module, "poll_disk_contains_all", side_effect=successful_poll):
            self.assertEqual(self.xml, self.module._disk_order(
                "Configuration.mdo", "catalogs", ["Beta", "Alpha"], fixture_rel="tests/tests"))

    @staticmethod
    def result(body):
        return HARNESS.Result({"result": {"structuredContent": body,
                               "isError": body.get("success") is False}})

    def test_governed_top_level_requires_refusals_and_an_allowed_sort_with_disk_assertions(self):
        action = ("Autosort (1C:Code style V8) keeps this list in alphabetical order. "
                  "If a manual order is needed, ask the user to turn it off for this list in "
                  "Project > Properties > V8 > Metadata objects sort > Auto sort > All top metadata objects; "
                  "do not change this setting yourself.")
        policy = {"plugin": "1C:Code style V8 (com.e1c.v8codestyle.autosort)",
                  "governs": "Configuration.catalogs", "setting": "all top-level lists",
                  "order": "ascending, natural", "effect": "The plugin maintains this list in alphabetical order.",
                  "userAction": action}
        expected = ["Catalog", "E861Alpha", "E861Beta", "E861Zulu"]
        self.module.metadata_collection_state = mock.Mock(return_value={"order": ["Catalog"], "autosort": policy})
        self.module.metadata_collection_order = mock.Mock(return_value=expected)
        self.module._disk_order = mock.Mock()
        self.module.tree_snapshot = mock.Mock(return_value="snapshot")
        self.module.assert_tree_unchanged = mock.Mock()

        def call(tool, args):
            notice = dict(policy)
            if tool == "create_metadata":
                notice["effect"] = "The plugin places the new object in its alphabetical position."
                return self.result({"success": True, "autosort": notice,
                                    "message": notice["effect"] + " " + action})
            if args.get("mode") in ("explicit", "move"):
                notice["effect"] = "The plugin would move the object anyway and revert a manual order."
                return self.result({"success": False, "autosort": notice,
                                    "message": notice["effect"] + " " + action,
                                    "error": "Cannot set manual order for Configuration.catalogs. " + action})
            return self.result({"success": True, "autosortCollectionCount": 1,
                                "message": notice["effect"] + " " + action,
                                "collections": [{"order": expected, "autosort": notice}]})

        self.module.call = mock.Mock(side_effect=call)
        self.module.test_configuration_autosort_policy_or_all_modes_persist_exact_disk_order()
        self.assertEqual(3, self.module._disk_order.call_count)
        self.assertEqual(2, self.module.assert_tree_unchanged.call_count)
        self.assertEqual([None, "explicit", "move"], [args[1].get("mode") for args, _kwargs
                         in self.module.call.call_args_list if args[0] == "sort_metadata"])

    def test_absent_autosort_top_level_exercises_the_entire_persistence_matrix(self):
        self.module.metadata_collection_state = mock.Mock(return_value={
            "order": ["Catalog"]})
        self.module.call = mock.Mock(return_value=self.result({"success": True}))
        self.module._all_modes = mock.Mock()
        self.module.test_configuration_autosort_policy_or_all_modes_persist_exact_disk_order()
        self.module._all_modes.assert_called_once_with("Configuration.Catalogs",
            "src/Configuration/Configuration.mdo", "catalogs", ["Catalog", "E861Zulu", "E861Alpha", "E861Beta"])

    def test_manual_member_matrix_falls_back_to_enum_values_only_when_attributes_are_governed(self):
        for active in (False, True):
            with self.subTest(attributes_governed=active):
                self.module.metadata_collection_state = mock.Mock(side_effect=[
                    ({"autosort": {}} if active else {}), {}])
                self.module.call = mock.Mock(return_value=self.result({"success": True}))
                self.module._all_modes = mock.Mock()
                self.module.test_member_all_modes_persist_exact_order_including_adjacent_noops()
                expected = "Enum.E861Modes.enumValues" if active else "Catalog.E861Modes.attributes"
                self.assertEqual(expected, self.module._all_modes.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
