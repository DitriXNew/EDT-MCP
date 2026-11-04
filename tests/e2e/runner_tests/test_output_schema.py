"""Output-schema validation and call() integration contracts, without EDT."""

import importlib.util
from pathlib import Path
import unittest
from unittest import mock


def fresh_harness():
    spec = importlib.util.spec_from_file_location(
        "output_schema_harness", Path(__file__).resolve().parents[1] / "harness.py")
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    return harness


H = fresh_harness()


class OutputSchemaValidatorTest(unittest.TestCase):
    def check(self, value, schema):
        H.validate_output_schema(value, schema, "probe")

    def rejects(self, value, schema, rule, path="$"):
        with self.assertRaises(AssertionError) as raised:
            self.check(value, schema)
        message = str(raised.exception)
        for text in ("probe structuredContent", path, rule):
            self.assertIn(text, message)

    def test_json_types_and_unions(self):
        samples = {"object": {}, "array": [], "string": "x", "integer": 2,
                   "number": 2.5, "boolean": True, "null": None}
        for kind, value in samples.items():
            with self.subTest(kind=kind):
                self.check(value, {"type": kind})
                wrong = "bad" if kind != "string" else 1
                self.rejects(wrong, {"type": kind}, "type")
        self.check(1.0, {"type": "integer"})
        self.rejects(True, {"type": "integer"}, "type")
        self.rejects(False, {"type": "number"}, "type")
        self.rejects(1.1, {"type": "integer"}, "type")
        for value in ([], {}):
            self.check(value, {"type": ["array", "object"]})
        self.rejects("x", {"type": ["array", "object"]}, "type")

    def test_nested_items_properties_and_required_report_exact_path(self):
        schema = {"type": "object", "required": ["rows"], "properties": {
            "rows": {"type": "array", "items": {"type": "object", "required": ["n"],
                "properties": {"n": {"type": "integer"}}}}}}
        self.check({"rows": [{"n": 1}]}, schema)
        self.rejects({}, schema, "required")
        self.rejects({"rows": [{}]}, schema, "required", '$["rows"][0]')
        self.rejects({"rows": [{"n": "bad"}]}, schema, "type", '$["rows"][0]["n"]')
        self.check({"anything": "allowed by default"}, {"type": "object"})
        self.check("not an object", {"required": ["n"], "properties": {"n": {"type": "integer"}}})

    def test_enum_uses_json_equality(self):
        self.check(1.0, {"enum": [1]})
        self.rejects(True, {"enum": [1]}, "enum")
        self.rejects({"x": [True]}, {"enum": [{"x": [1]}]}, "enum")
        self.check("set", {"enum": ["set", "unset"]})
        self.rejects("provided", {"enum": ["set", "unset"]}, "enum")

    def test_additional_properties_boolean_and_schema(self):
        self.check({}, {"additionalProperties": False})
        self.check({"x": 1}, {"properties": {"x": {}}, "additionalProperties": False})
        self.rejects({"x": 1}, {"additionalProperties": False}, "additionalProperties", '$["x"]')
        self.check({"x": 1}, {"additionalProperties": {"type": "integer"}})
        self.rejects({"x": "bad"}, {"additionalProperties": {"type": "integer"}}, "type")
        self.check({"x": None}, {"additionalProperties": True})

    def test_one_of_any_of_and_annotations(self):
        alternatives = [{"type": "number"}, {"type": "integer"}]
        self.check(1.5, {"oneOf": alternatives})
        self.rejects(1, {"oneOf": alternatives}, "oneOf")
        self.rejects("bad", {"oneOf": alternatives}, "oneOf")
        self.check(1, {"anyOf": alternatives})
        self.rejects("bad", {"anyOf": alternatives}, "anyOf")
        self.rejects(1, {"type": "integer", "enum": [2]}, "enum")
        self.check(None, {"description": "annotation", "default": 1})
        self.rejects(1, {"minimum": 2}, "unsupported schema keyword")


class OutputSchemaCallContractTest(unittest.TestCase):
    schema = {"type": "object", "required": ["applied"], "properties": {
        "applied": {"type": "array", "items": {"type": "string"}}}}

    def setUp(self):
        self.h = fresh_harness()
        self.h._record_attempt = mock.Mock()
        self.h._record_outcome = mock.Mock()

    def catalogue(self, name="modify_metadata", schema=None):
        return {"result": {"tools": [{"name": name, "outputSchema": self.schema if schema is None else schema}]}}

    def test_invalid_success_fails_after_outcome_is_recorded(self):
        raw = {"result": {"structuredContent": {"applied": {"rights": 1}}}}
        with mock.patch.object(self.h, "_post", side_effect=[raw, self.catalogue()]):
            with self.assertRaisesRegex(AssertionError, 'modify_metadata.*applied.*type'):
                self.h.call("modify_metadata", {})
        self.h._record_outcome.assert_called_once_with("modify_metadata", {}, False, raw["result"]["structuredContent"])

    def test_catalogue_is_loaded_once_and_paginated(self):
        raw = {"result": {"structuredContent": {"applied": ["comment"]}}}
        first = {"result": {"tools": [], "nextCursor": "page2"}}
        with mock.patch.object(self.h, "_post", side_effect=[raw, first, self.catalogue(), raw]) as post:
            self.h.call("modify_metadata", {})
            self.h.call("modify_metadata", {})
        self.assertEqual([mock.call("tools/call", {"name": "modify_metadata", "arguments": {}}),
                          mock.call("tools/list", {}), mock.call("tools/list", {"cursor": "page2"}),
                          mock.call("tools/call", {"name": "modify_metadata", "arguments": {}})], post.call_args_list)

    def test_errors_do_not_validate_success_schema(self):
        for raw in ({"error": {"message": "unknown tool"}},
                    {"result": {"isError": True, "structuredContent": {"error": "refused"}}},
                    {"result": {"structuredContent": {"success": False, "error": "refused"}}}):
            with self.subTest(raw=raw), mock.patch.object(self.h, "_post", return_value=raw) as post:
                self.h.call("modify_metadata", {})
                post.assert_called_once()

    def test_negotiated_text_only_success_needs_no_schema_validation(self):
        self.h._OUTPUT_SCHEMAS = {"modify_metadata": self.schema}
        raw = {"result": {"content": [{"type": "text", "text": "full JSON"}]}}
        with mock.patch.object(self.h, "_post", return_value=raw) as post:
            self.h.call("modify_metadata", {})
            post.assert_called_once()

    def test_explicit_null_structured_content_is_validated(self):
        self.h._OUTPUT_SCHEMAS = {"modify_metadata": self.schema}
        with mock.patch.object(self.h, "_post", return_value={"result": {"structuredContent": None}}):
            with self.assertRaisesRegex(AssertionError, 'modify_metadata.*type'):
                self.h.call("modify_metadata", {})

    def test_each_tool_uses_its_own_cached_schema(self):
        self.h._OUTPUT_SCHEMAS = {"first": {"type": "object"}, "second": self.schema,
                                  "unconstrained_tool": None}
        raw = {"result": {"structuredContent": {}}}
        with mock.patch.object(self.h, "_post", return_value=raw):
            self.h.call("first", {})
            with self.assertRaisesRegex(AssertionError, 'second.*required'):
                self.h.call("second", {})
            self.h.call("unconstrained_tool", {})

    def test_catalogue_failure_is_not_silently_cached(self):
        for raw in ({"error": {"message": "failed"}}, {"result": {}}):
            with mock.patch.object(self.h, "_post", return_value=raw):
                with self.assertRaisesRegex(AssertionError, "tools/list"):
                    self.h._output_schemas()
                self.assertIsNone(self.h._OUTPUT_SCHEMAS)

    def test_role_counts_pass_the_precise_union(self):
        counts = {"type": "object", "required": ["rights", "templates", "roleProperties"],
                  "properties": {key: {"type": "integer"} for key in ("rights", "templates", "roleProperties")}}
        schema = {"type": "object", "properties": {"applied": {"oneOf": [self.schema["properties"]["applied"], counts]}}}
        raw = {"result": {"structuredContent": {"applied": {"rights": 1, "templates": 0, "roleProperties": 0}}}}
        with mock.patch.object(self.h, "_post", side_effect=[raw, self.catalogue(schema=schema)]):
            self.h.call("modify_metadata", {})


    def test_toolset_enable_refreshes_newly_revealed_schema(self):
        self.h._OUTPUT_SCHEMAS = {"enable_toolset": self.schema}
        toggle = {"result": {"structuredContent": {"applied": ["metadata"]}}}
        invalid = {"result": {"structuredContent": {"applied": 42}}}
        catalogue = {"result": {"tools": [
            {"name": "enable_toolset", "outputSchema": self.schema},
            {"name": "modify_metadata", "outputSchema": self.schema}]}}

        def post(method, params):
            if method == "tools/list":
                return catalogue
            return toggle if params["name"] == "enable_toolset" else invalid

        with mock.patch.object(self.h, "_post", side_effect=post) as wire:
            self.h.call("enable_toolset", {"toolsets": ["metadata"]})
            self.assertIn(mock.call("tools/list", {}), wire.call_args_list)
            with self.assertRaisesRegex(AssertionError, "modify_metadata.*applied.*type"):
                self.h.call("modify_metadata", {})
        self.assertEqual(1, sum(c.args[0] == "tools/list" for c in wire.call_args_list))

    def test_toolset_disable_removes_hidden_tool_from_cached_catalogue(self):
        self.h._OUTPUT_SCHEMAS = {"enable_toolset": self.schema, "modify_metadata": self.schema}
        toggle = {"result": {"structuredContent": {"applied": ["metadata"]}}}
        with mock.patch.object(self.h, "_post", side_effect=[toggle, self.catalogue("enable_toolset")]):
            self.h.call("enable_toolset", {"toolsets": ["metadata"], "disable": True})
        self.assertNotIn("modify_metadata", self.h._OUTPUT_SCHEMAS)

    def test_initialize_discards_prior_session_schemas(self):
        self.h._OUTPUT_SCHEMAS = {"modify_metadata": {"type": "object"}}
        invalid = {"result": {"structuredContent": {"applied": 42}}}
        with mock.patch.object(self.h, "_notify") as notify, \
                mock.patch.object(self.h, "_post", side_effect=[{"result": {}}, invalid, self.catalogue()]) as wire:
            self.h.initialize()
            with self.assertRaisesRegex(AssertionError, "modify_metadata.*applied.*type"):
                self.h.call("modify_metadata", {})
        notify.assert_called_once_with("notifications/initialized", {})
        self.assertEqual("tools/list", wire.call_args_list[-1].args[0])

    def test_cache_miss_refreshes_before_validating_a_new_tool(self):
        self.h._OUTPUT_SCHEMAS = {"known": None}
        invalid = {"result": {"structuredContent": {"applied": 42}}}
        with mock.patch.object(self.h, "_post", side_effect=[invalid, self.catalogue()]) as wire:
            with self.assertRaisesRegex(AssertionError, "modify_metadata.*applied.*type"):
                self.h.call("modify_metadata", {})
        self.assertEqual(mock.call("tools/list", {}), wire.call_args_list[-1])

    def test_undiscovered_tool_fails_after_refresh(self):
        self.h._OUTPUT_SCHEMAS = {"known": None}
        raw = {"result": {"structuredContent": {}}}
        catalogue = {"result": {"tools": [{"name": "known"}]}}
        with mock.patch.object(self.h, "_post", side_effect=[raw, catalogue]) as wire:
            with self.assertRaisesRegex(AssertionError, "hidden_tool.*outputSchema.*not advertised"):
                self.h.call("hidden_tool", {})
        self.assertEqual(mock.call("tools/list", {}), wire.call_args_list[-1])

    def test_advertised_schema_free_tool_is_cached_explicitly(self):
        raw = {"result": {"structuredContent": {"arbitrary": "value"}}}
        catalogue = {"result": {"tools": [{"name": "schema_free"}]}}
        with mock.patch.object(self.h, "_post", side_effect=[raw, catalogue, raw]) as wire:
            self.h.call("schema_free", {})
            self.assertIn("schema_free", self.h._OUTPUT_SCHEMAS)
            self.assertIsNone(self.h._OUTPUT_SCHEMAS["schema_free"])
            self.h.call("schema_free", {})
        self.assertEqual(1, sum(c.args[0] == "tools/list" for c in wire.call_args_list))
