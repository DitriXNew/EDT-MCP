"""
e2e tests for search_in_code fileMask as a glob (issue #956, kind: read).

fileMask without '*' / '?' is a case-insensitive substring of the module path (as before). With
them it is a glob over the path WITHOUT src/: '*' and '?' stay within one folder or file name,
'**' crosses folders, a glob without '/' is matched against each single name, one with '/' from
the path start, and a matched folder selects everything under it. A backslash is the separator.
When the mask selects no module at all, the result says so instead of a bare zero.

Before #956 every glob below silently returned "Total: 0 matches" - the trap the issue reports.

Fixture truth (TestConfiguration/src, verified on disk):
  - CommonModules/Вычисление/Module.bsl -> "Функция Маркер() Экспорт" (the only "Маркер" line).
  - CommonModules/Error/Module.bsl      -> the single literal word "Error" (the only
    case-sensitive "Error" in the fixture's .bsl files).
"""

from harness import (
    call,
    assert_ok,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


def _search(query, file_mask, **more):
    args = {"projectName": PROJECT, "query": query, "fileMask": file_mask}
    args.update(more)
    r = call("search_in_code", args)
    assert_ok(r, "search_in_code query=%r fileMask=%r" % (query, file_mask))
    return r


@e2e_test(tool="search_in_code", kind="read")
def test_file_mask_star_name_star_glob_selects_the_module():
    """The issue's first example: '*<Name>*' selects the module folder (case-insensitively,
    Cyrillic included) instead of matching nothing."""
    r = _search("Маркер", "*вычисление*")
    assert_contains(r.text, "**Total:** 1 matches in 1 files",
                    "the glob must select CommonModules/Вычисление and find its one line")
    assert_contains(r.text, "### CommonModules/Вычисление/Module.bsl",
                    "the selected module must be the one reported")
    assert_not_contains(r.text, "No matches found.", "a real hit must not render the sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_file_mask_double_star_path_glob_selects_the_exact_module():
    """The issue's second example: '**/<Name>/<File>.bsl' selects exactly that module."""
    r = _search("Error", "**/Error/Module.bsl", caseSensitive=True, outputMode="files")
    assert_contains(r.text, "**Total matches:** 1 in **1** files",
                    "the path glob must select CommonModules/Error/Module.bsl")
    assert_contains(r.text, "CommonModules/Error/Module.bsl",
                    "the files table must list the selected module")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_file_mask_folder_glob_and_backslash_mask_select_modules():
    """'CommonModules/*' selects every common module (a matched folder selects what is under it);
    a backslash-separated substring is read as a path, not as a string no path can contain."""
    r = _search("Error", "CommonModules/*", caseSensitive=True, outputMode="count")
    assert_contains(r.text, "**Total matches:** 1 in **1** files",
                    "the folder glob must keep CommonModules/Error/Module.bsl")
    r = _search("Error", "CommonModules\\Error", caseSensitive=True, outputMode="count")
    assert_contains(r.text, "**Total matches:** 1 in **1** files",
                    "a backslash in the mask must act as the path separator")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_file_mask_substring_mode_is_unchanged():
    """A mask without '*' / '?' is still a case-insensitive substring of the path."""
    r = _search("Error", "commonmodules/error", caseSensitive=True, outputMode="count")
    assert_contains(r.text, "**Total matches:** 1 in **1** files",
                    "a substring mask must keep working exactly as before")
    assert_not_contains(r.text, "fileMask", "a mask that selected a module must not warn")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_file_mask_that_selects_no_module_says_so():
    """A mask that rejects every module must say that nothing was searched, naming a real path
    shape, instead of a bare zero that reads as 'the text is absent'."""
    r = _search("Error", "*NoSuchModule956*")
    assert_contains(r.text, "**Total:** 0 matches in 0 files", "nothing can match")
    assert_contains(r.text, "fileMask `*NoSuchModule956*` matched none of the",
                    "the result must say the mask selected no module")
    assert_contains(r.text, "so nothing was searched (paths look like `",
                    "the warning must show the path shape the mask is matched against")
    assert_contains(r.text, "No matches found.", "the sentinel stays")
    assert_no_diff("a read tool must not touch the project on disk")
