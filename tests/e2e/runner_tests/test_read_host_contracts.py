"""Checks the exact-response verifier; live tools are exercised by both stdio drivers."""

import copy
import json
from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import read_host_contracts as C
from harness import Result, E2EAssertion


class ReadHostContractsTest(unittest.TestCase):
    def invoke(self, kind, changed_tool=None):
        cases = C.baseline_cases()

        def call(tool, arguments):
            if tool == "list_modules" and arguments == {"projectName": "TestConfiguration"}:
                return Result({"result": {"content": [{"text": "CommonModules/Calc/Module.bsl\n"
                    "CommonModules/Вычисление/Module.bsl\nConfiguration/ManagedApplicationModule.bsl"}]}})
            matching = [case for case in cases.values()
                        if case["tool"] == tool and case["arguments"] == arguments]
            self.assertEqual(1, len(matching))
            response = copy.deepcopy(matching[0]["response"])
            if kind == "HEADLESS" and tool == "get_symbol_info":
                response = next(case["response"] for case in json.loads(C.HEADLESS_BASELINE.read_text(encoding="utf-8"))["cases"].values() if case["arguments"] == arguments)
            text = response["text"]
            if kind == "HEADLESS" and tool == "read_module_source":
                text = text.partition("\n## Extension interception\n")[0]
            if tool == changed_tool:
                text += "\nwrong formatter output"
            return Result({"result": {"content": [{"type": "text", "text": text}],
                                      "isError": response["isError"],
                                      "structuredContent": response["structuredContent"]}})
        return call

    def test_pins_are_stable_windowed_responses_and_exclude_the_annotation_hover_defect(self):
        document = json.loads(C.BASELINE.read_text(encoding="utf-8"))
        self.assertEqual("802549c6c5165d1784e373638a8939c8af9d43f8", document["commit"])
        self.assertEqual("WINDOWED", document["hostKind"])
        self.assertEqual(22, len(document["cases"]))
        self.assertNotIn("modules-all", document["cases"])
        content = json.dumps(document["cases"], ensure_ascii=False)
        self.assertIsNone(re.search(r"[A-Z]:[\\/]|@[0-9a-f]{6,}|20[0-9]{2}-[0-9]{2}-[0-9]{2}", content))
        for case in document["cases"].values():
            if case["tool"] == "get_symbol_info":
                self.assertEqual(2, case["arguments"]["column"])
                self.assertNotIn("BslAnnotationInfo@", case["response"]["text"])

    def test_windowed_driver_checks_all_pins_and_filesystem_content(self):
        self.assertEqual(23, C.run_contracts(self.invoke("WINDOWED"), "WINDOWED"))

    def test_headless_driver_checks_each_moved_tool_and_cyrillic_content(self):
        self.assertEqual(23, C.run_contracts(self.invoke("HEADLESS"), "HEADLESS"))
        for tool in C.MOVED_TOOLS:
            requests = [case["arguments"] for case in C.baseline_cases().values() if case["tool"] == tool]
            self.assertIn("Вычисление", json.dumps(requests, ensure_ascii=False))

    def test_each_tool_contract_rejects_a_changed_result(self):
        for tool, contract in C.CONTRACTS.items():
            with self.subTest(tool=tool):
                with self.assertRaisesRegex(AssertionError, "formatter contract changed"):
                    contract(self.invoke("WINDOWED", tool), "WINDOWED")
        for tool in (*C.MOVED_TOOLS, "read_module_source"):
            with self.subTest(headless_tool=tool):
                with self.assertRaisesRegex(AssertionError, "formatter contract changed"):
                    C.CONTRACTS[tool](self.invoke("HEADLESS", tool), "HEADLESS")

    def test_contract_does_not_normalize_trailing_newlines_or_line_endings(self):
        for transform in (lambda text: text.rstrip("\n"), lambda text: text.replace("\n", "\r\n")):
            for kind in ("WINDOWED", "HEADLESS"):
                invoke = self.invoke(kind)

                def changed(tool, arguments):
                    result = invoke(tool, arguments)
                    result.result["content"][0]["text"] = transform(result.text)
                    return Result(result.raw)

                with self.subTest(kind=kind, transform=transform):
                    with self.assertRaisesRegex(AssertionError, "formatter contract changed"):
                        C.contract_get_metadata_objects(changed, kind)

    def test_filesystem_mode_requires_real_content_without_pinning_traversal_order(self):
        invoke = self.invoke("HEADLESS")

        def empty_filesystem(tool, arguments):
            if tool == "list_modules" and arguments == {"projectName": "TestConfiguration"}:
                return Result({"result": {"content": [{"text": "No modules found."}]}})
            return invoke(tool, arguments)

        with self.assertRaises(E2EAssertion):
            C.contract_list_modules(empty_filesystem, "HEADLESS")


if __name__ == "__main__":
    unittest.main()
