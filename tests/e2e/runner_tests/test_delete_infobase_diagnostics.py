"""Offline contracts for the gated directory probes and their EDT log reader."""

import importlib.util
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock


E2E_DIR = Path(__file__).resolve().parents[1]
HARNESS_SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_delete_diagnostics_harness", E2E_DIR / "harness.py")
HARNESS = importlib.util.module_from_spec(HARNESS_SPEC)
HARNESS_SPEC.loader.exec_module(HARNESS)
TOOL_SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_delete_diagnostics", E2E_DIR / "tools" / "test_delete_infobase.py")
DIAGNOSTICS = importlib.util.module_from_spec(TOOL_SPEC)
with mock.patch.dict("sys.modules", {"harness": HARNESS}):
    TOOL_SPEC.loader.exec_module(DIAGNOSTICS)


class DeleteInfobaseDiagnosticsTest(unittest.TestCase):
    def test_missing_marker_probe_skips_before_creating_or_registering_a_directory(self):
        with mock.patch.object(HARNESS, "LIVE_INFOBASE", False), \
                mock.patch.object(DIAGNOSTICS, "_kept_directory_case") as probe:
            with self.assertRaises(HARNESS.E2ESkip) as skipped:
                DIAGNOSTICS.test_directory_without_database_marker_is_kept_with_a_warning()
            self.assertIn("EDT_MCP_LIVE_INFOBASE=1", str(skipped.exception))
            probe.assert_not_called()

    def test_locked_probe_skips_on_windows_before_creating_or_registering_a_directory(self):
        with mock.patch.object(HARNESS, "LIVE_INFOBASE", False), \
                mock.patch.object(DIAGNOSTICS, "os", types.SimpleNamespace(name="nt")), \
                mock.patch.object(DIAGNOSTICS, "_kept_directory_case") as probe:
            with self.assertRaises(HARNESS.E2ESkip) as skipped:
                DIAGNOSTICS.test_locked_database_directory_is_kept_with_a_warning_without_stack()
            self.assertIn("EDT_MCP_LIVE_INFOBASE=1", str(skipped.exception))
            probe.assert_not_called()

    def test_attended_probes_reach_their_respective_directory_scenario(self):
        scenarios = (
            (DIAGNOSTICS.test_directory_without_database_marker_is_kept_with_a_warning, False),
            (DIAGNOSTICS.test_locked_database_directory_is_kept_with_a_warning_without_stack, True),
        )
        for test, locked in scenarios:
            with self.subTest(locked=locked), mock.patch.object(HARNESS, "LIVE_INFOBASE", True), \
                    mock.patch.object(DIAGNOSTICS, "os", types.SimpleNamespace(name="nt")), \
                    mock.patch.object(DIAGNOSTICS, "_kept_directory_case") as probe:
                test()
                probe.assert_called_once_with(locked=locked)

    def test_attended_locked_probe_still_requires_windows_delete_sharing(self):
        with mock.patch.object(HARNESS, "LIVE_INFOBASE", True), \
                mock.patch.object(DIAGNOSTICS, "os", types.SimpleNamespace(name="posix")), \
                mock.patch.object(DIAGNOSTICS, "_kept_directory_case") as probe:
            with self.assertRaises(HARNESS.E2ESkip) as skipped:
                DIAGNOSTICS.test_locked_database_directory_is_kept_with_a_warning_without_stack()
            self.assertIn("Windows", str(skipped.exception))
            probe.assert_not_called()

    @staticmethod
    def _warning(directory, detail):
        return (
            "!ENTRY com.ditrix.edt.mcp.server 2 0 2026-10-19 12:00:00.000\n"
            "!MESSAGE delete_infobase: could not delete the database directory '%s' "
            "(non-fatal — the registration is already removed; delete it manually): %s\n"
        ) % (directory, detail)

    def test_warning_reader_tolerates_invalid_utf8_in_current_and_rotated_logs(self):
        for filename in (".log", ".bak_1.log"):
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as workspace:
                metadata = Path(workspace) / ".metadata"
                metadata.mkdir()
                directory = Path(workspace) / "unique-directory-probe"
                warning = self._warning(directory, "AccessDeniedException: " + str(directory))
                (metadata / filename).write_bytes(b"unrelated bundle output: \xff\n" + warning.encode("utf-8"))

                DIAGNOSTICS._assert_directory_warning(
                    workspace, directory, "could not delete the database directory", with_detail=True)

    def test_warning_reader_rejects_a_bare_path_without_the_exception_type(self):
        with tempfile.TemporaryDirectory() as workspace:
            metadata = Path(workspace) / ".metadata"
            metadata.mkdir()
            directory = Path(workspace) / "unique-directory-probe"
            (metadata / ".log").write_text(
                self._warning(directory, str(directory / "1Cv8.1CD")), encoding="utf-8")

            with self.assertRaisesRegex(AssertionError, "exception type"):
                DIAGNOSTICS._assert_directory_warning(
                    workspace, directory, "could not delete the database directory", with_detail=True)


if __name__ == "__main__":
    unittest.main()
