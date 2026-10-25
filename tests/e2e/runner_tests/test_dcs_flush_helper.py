"""Offline checks for Template.dcs polling during an EDT export."""

from contextlib import ExitStack
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
import xml.etree.ElementTree as ET


E2E_DIR = Path(__file__).resolve().parents[1]


def _load(name, relative):
    spec = importlib.util.spec_from_file_location(name, E2E_DIR / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


HARNESS = _load("edt_mcp_dcs_flush_harness", "harness.py")
with mock.patch.dict("sys.modules", {"harness": HARNESS}):
    DCS = _load("edt_mcp_dcs_flush_structure", "tools/test_dcs.py")
with mock.patch.dict("sys.modules", {"harness": HARNESS, "tools.test_dcs": DCS}):
    FLUSH = _load("edt_mcp_dcs_flush", "tools/test_dcs_parameter_reproducibility.py")


class DcsFlushHelperTest(unittest.TestCase):
    relative = "src/Reports/Example/Templates/Main/Template.dcs"
    model = "<schema><value>ready</value></schema>"

    def setUp(self):
        self.patches = ExitStack()
        self.addCleanup(self.patches.close)
        self.patches.enter_context(mock.patch.object(FLUSH, "PROJECT_DIR", str(E2E_DIR)))
        self.patches.enter_context(mock.patch.object(FLUSH, "_poll_report_dcs", return_value=self.relative))
        self.model_read = self.patches.enter_context(mock.patch.object(FLUSH, "_xml", return_value=self.model))
        self.read = self.patches.enter_context(mock.patch.object(Path, "read_bytes"))
        self.clock = self.patches.enter_context(mock.patch.object(FLUSH, "time"))
        self.clock.monotonic.return_value = 100.0

    def test_retries_an_empty_then_truncated_read_and_returns_the_matching_bytes(self):
        payload = b"\xef\xbb\xbf" + self.model.encode("utf-8")
        self.read.side_effect = [b"", b"<schema><value>", payload]

        self.assertEqual((E2E_DIR / self.relative, payload), FLUSH._flushed_bytes("Example", "Report.Example"))

        self.assertEqual(3, self.read.call_count)
        self.assertEqual([mock.call(0.2)] * 2, self.clock.sleep.call_args_list)
        self.model_read.assert_called_once_with("Report.Example")

    def test_retries_missing_unreadable_invalid_utf8_and_stale_exports(self):
        for observation in (FileNotFoundError("export replaced"), PermissionError("export locked"),
                            b"<schema>\xe2\x82", b"<schema><value>stale</value></schema>"):
            with self.subTest(observation=observation):
                self.read.reset_mock()
                self.clock.sleep.reset_mock()
                payload = self.model.encode("utf-8")
                self.read.side_effect = [observation, payload]

                self.assertEqual((E2E_DIR / self.relative, payload),
                                 FLUSH._flushed_bytes("Example", "Report.Example"))

                self.assertEqual(2, self.read.call_count)
                self.clock.sleep.assert_called_once_with(0.2)

    def test_deadline_names_the_latest_observation(self):
        observations = (
            (b"", "empty file"),
            (b"<schema><value>", "ParseError: no element found"),
            (b"<schema>\xe2\x82", "UnicodeDecodeError:"),
            (FileNotFoundError("export replaced"), "FileNotFoundError: export replaced"),
            (PermissionError("export locked"), "PermissionError: export locked"),
            (b"<schema><value>stale</value></schema>", "XML structure differs from the schema model"),
        )
        for observation, diagnostic in observations:
            with self.subTest(diagnostic=diagnostic):
                self.read.reset_mock()
                self.clock.sleep.reset_mock()
                self.read.side_effect = [b"<old/>", observation]
                self.clock.monotonic.side_effect = [100.0, 129.9, 130.0]

                with self.assertRaises(AssertionError) as raised:
                    FLUSH._flushed_bytes("Example", "Report.Example")

                message = str(raised.exception)
                self.assertIn("Template.dcs export did not catch up with the schema model", message)
                self.assertIn("last read: " + diagnostic, message)
                self.assertEqual(2, self.read.call_count)
                self.clock.sleep.assert_called_once_with(0.2)

    def test_structurally_equal_xml_keeps_the_exact_successful_read(self):
        self.model_read.return_value = (
            '<schema id="11111111-1111-1111-1111-111111111111"><value>ready</value></schema>')
        payload = (b'\xef\xbb\xbf<schema id="22222222-2222-2222-2222-222222222222">\n'
                   b'  <value>ready</value>\n</schema>')
        self.read.return_value = payload

        self.assertEqual((E2E_DIR / self.relative, payload), FLUSH._flushed_bytes("Example", "Report.Example"))

        self.read.assert_called_once_with()
        self.clock.sleep.assert_not_called()

    def test_invalid_model_xml_fails_before_polling_the_disk(self):
        self.model_read.return_value = "<schema>"

        with self.assertRaises(ET.ParseError):
            FLUSH._flushed_bytes("Example", "Report.Example")

        self.read.assert_not_called()
        self.clock.sleep.assert_not_called()

    def test_unrelated_read_errors_are_not_hidden_as_an_export_in_progress(self):
        self.read.side_effect = RuntimeError("unexpected reader failure")

        with self.assertRaisesRegex(RuntimeError, "unexpected reader failure"):
            FLUSH._flushed_bytes("Example", "Report.Example")

        self.read.assert_called_once_with()
        self.clock.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
