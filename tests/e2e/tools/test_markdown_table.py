"""
Regression tests for harness.split_markdown_row - the shared parser for tables THIS
project renders (kind: read, pseudo-tool: not an MCP tool, like _coverage_ratchet).

WHY THIS FILE EXISTS
--------------------
Production escapes a literal '|' inside a cell as '\\|' (MarkdownUtils.escapeForTable)
so it cannot be mistaken for a column delimiter. A naive str.split("|") does not know
that escape and cuts such a row at the WRONG point: the row yields MORE cells than the
table has columns, and a caller filtering on an exact column count (e.g.
test_apply_quick_fix's fixable-marker discovery, which requires exactly 7) silently
DROPS it. The row is real and actionable, but invisible to the test - so the test can
report "nothing to do" while a genuine marker sits right there.

That failure mode is INVISIBLE to a discovery-based e2e test: whether any live fixture
row happens to contain a '|' at all is luck, and when no fixable marker is found the
consuming test SKIPs. A parser regression would therefore stay green. These tests pin
the escaping contract DETERMINISTICALLY - fixed input strings, no live fixture, no
skip - so the case the parser was written for is actually proven.

They exercise the parser directly (pure string in, cells out); the live server is not
involved, so they cannot flake on fixture content.
"""

from harness import e2e_test, split_markdown_row, _fail

# The 7-column detailed shape get_project_errors renders (the consumer that motivated
# the escape-aware parser): Description | Location | Module path | Line | Check code |
# Fix registered | Has docs.
_COLUMNS = 7


def _expect(line, expected, ctx):
    cells = split_markdown_row(line)
    if cells != expected:
        _fail("%s\n  row      : %r\n  expected : %r\n  actual   : %r"
              % (ctx, line, expected, cells))


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_plain_row_splits_on_every_column_delimiter():
    """The baseline that must not regress while adding escape awareness: an ordinary
    row still splits into exactly one cell per column."""
    row = "| desc | loc | CommonModules/Calc/Module.bsl | 12 | `doc-comment` | yes | false |"
    _expect(row,
            ["desc", "loc", "CommonModules/Calc/Module.bsl", "12", "`doc-comment`", "yes", "false"],
            "a plain row must split on every unescaped delimiter")


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_escaped_pipe_in_description_keeps_the_column_count():
    """THE regression case: an escaped '|' inside Description must NOT be treated as a
    delimiter. Before the fix this row parsed to 8 cells and the consumer - which keeps
    only rows with exactly 7 - dropped it silently."""
    row = ("| left \\| right | loc | CommonModules/Calc/Module.bsl | 12 "
           "| `doc-comment` | yes | false |")
    cells = split_markdown_row(row)
    if len(cells) != _COLUMNS:
        _fail("an escaped pipe in Description must not add a column: expected %d cells, got %d: %r"
              % (_COLUMNS, len(cells), cells))
    if cells[0] != "left | right":
        _fail("the unescaped cell value must equal the text production was given, got %r" % cells[0])


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_escaped_pipe_in_location_keeps_the_column_count():
    """The same escape in a DIFFERENT column (Location) - an object presentation can
    legitimately contain a '|' too, so the guard cannot be Description-specific."""
    row = ("| desc | Catalog.A \\| Catalog.B | CommonModules/Calc/Module.bsl | 12 "
           "| `doc-comment` | yes | false |")
    cells = split_markdown_row(row)
    if len(cells) != _COLUMNS:
        _fail("an escaped pipe in Location must not add a column: expected %d cells, got %d: %r"
              % (_COLUMNS, len(cells), cells))
    if cells[1] != "Catalog.A | Catalog.B":
        _fail("the unescaped Location must equal the original text, got %r" % cells[1])


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_several_escaped_pipes_in_one_cell():
    """More than one escape in the SAME cell: every one is unescaped and none of them
    splits the row (an off-by-one in the lookbehind would break exactly here)."""
    row = ("| a \\| b \\| c \\| d | loc | CommonModules/Calc/Module.bsl | 12 "
           "| `doc-comment` | yes | false |")
    cells = split_markdown_row(row)
    if len(cells) != _COLUMNS:
        _fail("three escaped pipes in one cell must not add columns: expected %d, got %d: %r"
              % (_COLUMNS, len(cells), cells))
    if cells[0] != "a | b | c | d":
        _fail("every escape in the cell must be unescaped, got %r" % cells[0])


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_escaped_pipe_in_the_last_cell_does_not_eat_the_row_boundary():
    """The boundary case the 'strip by position, not by character' rule protects: an
    escape at the very END of the last cell, right before the closing delimiter."""
    row = "| desc | loc | Module.bsl | 12 | `c` | yes | a \\| b |"
    cells = split_markdown_row(row)
    if len(cells) != _COLUMNS:
        _fail("an escaped pipe in the LAST cell must not add a column: expected %d, got %d: %r"
              % (_COLUMNS, len(cells), cells))
    if cells[-1] != "a | b":
        _fail("the last cell must unescape to the original text, got %r" % cells[-1])


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_empty_cells_are_preserved_as_empty_strings():
    """Metadata-only problems render empty Module path / Line cells - those must stay
    as positional empty cells, not collapse and shift every later column left."""
    row = "| desc | Catalog.Products | |  | `some-check` | | false |"
    _expect(row, ["desc", "Catalog.Products", "", "", "`some-check`", "", "false"],
            "empty cells must be preserved positionally")


@e2e_test(tool="_markdown_table_parser", kind="read")
def test_non_table_lines_yield_no_cells():
    """Prose, headers and blank lines around the table must not parse as rows."""
    for line in ("", "   ", "# Configuration Problems", "Some prose about | pipes.", None):
        cells = split_markdown_row(line)
        if cells:
            _fail("a non-row line must yield no cells: %r -> %r" % (line, cells))
