"""The windowed get_symbol_info pin follows EDT's UI language (issue #935).

EDT renders the editor hover in its UI language, so a Russian-UI stand answers "Функция ... Параметры"
where the English pin says "Function ... Parameters". The contract picks the Russian pin only when the
hover's own type links say it is Russian, and still compares byte for byte in either language.
"""

import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import read_host_contracts as C
from harness import Result


def _ru_cases():
    return json.loads(C.RU_HOVER_BASELINE.read_text(encoding="utf-8"))["cases"]


def _invoke(responses):
    """A fake MCP call answering get_symbol_info from the given label -> text map."""
    cases = C.baseline_cases()

    def call(tool, arguments):
        label = next(name for name, case in cases.items()
                     if case["tool"] == tool and case["arguments"] == arguments)
        return Result({"result": {"content": [{"type": "text", "text": responses[label]}],
                                  "isError": False, "structuredContent": None}})
    return call


def _symbol_labels():
    return [label for label, case in C.baseline_cases().items() if case["tool"] == "get_symbol_info"]


class HoverLanguageTest(unittest.TestCase):
    def test_english_pins_read_as_english(self):
        for label in _symbol_labels():
            with self.subTest(label=label):
                self.assertEqual("en", C.hover_language(C.baseline_cases()[label]["response"]["text"]))

    def test_russian_pins_read_as_russian(self):
        for label, case in _ru_cases().items():
            with self.subTest(label=label):
                self.assertEqual("ru", C.hover_language(case["response"]["text"]))

    def test_an_answer_without_type_links_or_with_mixed_labels_is_unrecognised(self):
        self.assertIsNone(C.hover_language("Function Add(A, B)"))
        self.assertIsNone(C.hover_language(None))
        mixed = ("[Number](v8help:/8.5.1/SyntaxHelperLanguage/def_Number) "
                 "[Число](v8help:/8.5.1/SyntaxHelperLanguage/def_Number)")
        self.assertIsNone(C.hover_language(mixed))
        self.assertIsNone(C.hover_language("[Numbr](v8help:/8.5.1/SyntaxHelperLanguage/def_Number)"))


class LocalizedSymbolPinTest(unittest.TestCase):
    def test_the_russian_pin_covers_exactly_the_english_symbol_cases(self):
        english = {label: C.baseline_cases()[label]["arguments"] for label in _symbol_labels()}
        russian = {label: case["arguments"] for label, case in _ru_cases().items()}
        self.assertEqual(english, russian)
        for case in _ru_cases().values():
            self.assertEqual("get_symbol_info", case["tool"])

    def test_english_ui_answers_pass(self):
        english = {label: C.baseline_cases()[label]["response"]["text"] for label in _symbol_labels()}
        self.assertEqual(len(english), C.contract_get_symbol_info(_invoke(english), "WINDOWED"))

    def test_russian_ui_answers_pass(self):
        russian = {label: case["response"]["text"] for label, case in _ru_cases().items()}
        self.assertEqual(len(russian), C.contract_get_symbol_info(_invoke(russian), "WINDOWED"))

    def test_a_changed_russian_answer_still_fails_byte_for_byte(self):
        russian = {label: case["response"]["text"] for label, case in _ru_cases().items()}
        for label in russian:
            with self.subTest(label=label):
                changed = copy.deepcopy(russian)
                changed[label] = changed[label].replace("\\>\\*", ">*", 1)
                self.assertNotEqual(russian[label], changed[label])
                with self.assertRaisesRegex(AssertionError, "formatter contract changed.*hover language ru"):
                    C.contract_get_symbol_info(_invoke(changed), "WINDOWED")

    def test_russian_prose_under_english_type_labels_is_compared_with_the_english_pin(self):
        # Only the type links decide the language: Russian prose with English labels is not a
        # Russian hover, so it meets the English pin and fails there.
        russian = {label: case["response"]["text"] for label, case in _ru_cases().items()}
        hybrid = {label: text.replace("[Число]", "[Number]")
                  for label, text in russian.items()}
        with self.assertRaisesRegex(AssertionError, "formatter contract changed.*hover language en"):
            C.contract_get_symbol_info(_invoke(hybrid), "WINDOWED")

    def test_headless_symbol_pin_ignores_the_ui_language(self):
        russian = {label: case["response"]["text"] for label, case in _ru_cases().items()}
        with self.assertRaisesRegex(AssertionError, "HEADLESS formatter contract changed"):
            C.contract_get_symbol_info(_invoke(russian), "HEADLESS")


if __name__ == "__main__":
    unittest.main()
