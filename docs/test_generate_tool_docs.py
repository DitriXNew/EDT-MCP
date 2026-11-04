"""Exercise regeneration without a live server or a writable repository fixture."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "generate_tool_docs", Path(__file__).with_name("generate_tool_docs.py"))
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class ReadmeRegenerationTest(unittest.TestCase):
    def regenerate(self, root, tools, members):
        docs = root / "docs"
        docs.mkdir(exist_ok=True)
        with patch.object(generator, "HERE", str(docs)), \
                patch.object(generator, "OUT_DIR", str(docs / "tools")), \
                patch.object(generator, "rpc", return_value={"tools": tools}), \
                patch.object(generator, "call_structured", return_value={
                    "progressiveDisclosure": False,
                    "toolsets": [{"title": "Core", "tools": members}]}), \
                patch.object(generator, "read_guide", side_effect=lambda _url, name:
                             "# " + name + "\n\nFresh advertised description.\n"), \
                patch("sys.argv", ["generate_tool_docs.py"]), \
                contextlib.redirect_stdout(io.StringIO()):
            generator.main()
        return (root / "README.md").read_text(encoding="utf-8")

    def test_preserves_full_overview_while_refreshing_membership_and_tool_pages(self):
        overview = ("Read the saved source using `projectName` and `modulePath`; "
                    "`startLine`/`endLine` are inclusive. Return the entire revision hash "
                    "even for a limited range, and preserve a literal \\| in this table cell.")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text(
                "Introduction.\n<!-- TOOLS-INDEX:START -->\n"
                "| [`existing`](docs/tools/existing.md) | " + overview + " |\n"
                "| [`removed`](docs/tools/removed.md) | Obsolete entry. |\n"
                "<!-- TOOLS-INDEX:END -->\nAfterword.\n", encoding="utf-8")
            text = self.regenerate(root, [
                {"name": "existing", "description": "Fresh advertised description."},
                {"name": "added", "description": "Describe a newly registered tool."}],
                ["existing", "added"])
            self.assertIn(overview, text)
            self.assertIn("Describe a newly registered tool.", text)
            self.assertNotIn("removed.md", text)
            self.assertIn("**2 tools**", text)
            self.assertTrue(text.startswith("Introduction.\n"))
            self.assertTrue(text.endswith("Afterword.\n"))
            index = (root / "docs/tools/README.md").read_text(encoding="utf-8")
            self.assertIn("Fresh advertised description.", index)
            self.assertNotIn(overview, index)
            page = (root / "docs/tools/existing.md").read_text(encoding="utf-8")
            self.assertEqual(generator.assemble_page(
                "# existing\n\nFresh advertised description.\n"), page)

    def test_repeated_generation_keeps_one_disabled_annotation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text(
                "<!-- TOOLS-INDEX:START -->\n"
                "| [`hidden`](docs/tools/hidden.md) | Maintained overview."
                " *(not enabled by default)* |\n<!-- TOOLS-INDEX:END -->\n",
                encoding="utf-8")
            tools = [{"name": "visible", "description": "Visible tool."}]
            first = self.regenerate(root, tools, ["hidden", "visible"])
            second = self.regenerate(root, tools, ["hidden", "visible"])
            self.assertEqual(first, second)
            self.assertEqual(1, second.count("*(not enabled by default)*"))
            self.assertIn("Maintained overview.", second)


if __name__ == "__main__":
    unittest.main()
