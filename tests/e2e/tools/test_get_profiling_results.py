"""Real persisted EDT measurements; no infobase is needed for these scenarios."""
import re
from pathlib import Path
from harness import call, assert_ok, assert_error, assert_error_quality, assert_contains, assert_no_diff, e2e_test, REPO_ROOT

_FIXTURE = Path(REPO_ROOT) / 'tests' / 'fixtures' / 'profiling'
_BASE = '60f80e45-8156-4b11-bad5-9207611a9033'
_COMPARED = '71da0ce9-2993-4969-9b55-5cfa35d770f4'


def _seed():
    result = call('manage_profiling_results', {'mode': 'import', 'importPath': str(_FIXTURE)})
    assert_ok(result, 'import real EDT measurement fixture')
    for measurement in (_BASE, _COMPARED):
        assert_contains(result.text, measurement, 'import reports both fixture UUIDs')
    return result


def _read(**arguments):
    result = call('get_profiling_results', {'measurementId': _BASE, **arguments})
    assert_ok(result, 'read selected measurement')
    return result.text


@e2e_test(tool='get_profiling_results', kind='read')
def test_profiling_fixture_summary_paging_and_compare():
    _seed()
    listed = call('get_profiling_results', {'mode': 'list', 'limit': 1})
    assert_ok(listed, 'paged measurement list')
    assert_contains(listed.text, 'Showing 1-1 of', 'list cap is explicit')
    assert_contains(listed.text, 'offset=1', 'list offers next page')
    assert_contains(listed.text, 'Infobase', 'list reports infobase')
    summary = _read(top=2, applicationId='unknown-profiling-e2e')
    for fragment in ('Measurement: `' + _BASE + '`', '2026-06-06', 'Time unit: ms', 'Top lines by pure time',
                     'Top lines by call count', 'Top lines with server calls', 'Top methods by own',
                     'Top modules by own', 'showing 2 of', 'Pure time ms', 'Server', 'profilingActive: false',
                     'Invocations (est.)', 'loop header'):
        assert_contains(summary, fragment, 'summary contract')
    first = _read(mode='lines', moduleFilter='tests_SampleTests', limit=1, responseFormat='detailed')
    second = _read(mode='lines', moduleFilter='tests_SampleTests', limit=1, offset=1, responseFormat='detailed')
    assert_contains(first, 'Showing 1-1 of 2', 'method has two real measured lines')
    assert_contains(first, 'offset=1', 'lines offers next page')
    assert_contains(second, 'Showing 2-2 of 2.', 'second page reaches the end')
    assert 'next page' not in second, second
    rows1 = [line for line in first.splitlines() if line.startswith('| TestConfiguration')]
    rows2 = [line for line in second.splitlines() if line.startswith('| TestConfiguration')]
    assert len(rows1) == len(rows2) == 1 and rows1 != rows2, (rows1, rows2)
    callers = _read(mode='callers', methodFilter='ОжидаетЧто', moduleFilter='tests_SampleTests')
    for fragment in ('Callers of `ОжидаетЧто`', 'by inclusive time', 'Inclusive ms', 'ЮТест.ОжидаетЧто(2 + 2)'):
        assert_contains(callers, fragment, 'callers contract')
    concise = _read(mode='lines', moduleFilter='tests_SampleTests', limit=1)
    assert '| Method |' not in concise and '| Source (shortened) |' not in concise, concise
    compared = _read(mode='compare', baseId=_BASE, comparedId=_COMPARED,
                     moduleFilter='tests_SampleTests', limit=1)
    for fragment in ('Base → compared', _BASE, _COMPARED, 'slowed-down methods', 'sped-up methods',
                     'Base calls', 'Compared calls', 'Unmatched elements:', 'number and trimmed text'):
        assert_contains(compared, fragment, 'EDT comparison contract')
    for output in (listed.text, summary, first, second, callers, concise, compared):
        lines = output.splitlines()
        for index, line in enumerate(lines[:-1]):
            if line.startswith('|') and not lines[index + 1].startswith('|'):
                assert lines[index + 1] == '', 'blank line required after table: ' + output
        assert not re.search(r'(?m)^ \(showing|^Remaining:', output), output
        assert not re.search(r'\| -?\d+\.\d{6} \|', output), 'milliseconds must use 3 decimals: ' + output
    assert 'performance ticks' not in compared and 'line Durations' not in compared, compared
    assert_no_diff('profiling reads preserve project sources')


@e2e_test(tool='get_profiling_results', kind='read')
def test_profiling_validation_and_unknown_measurement():
    for arguments, bad, hint in [
        ({'mode': 'export'}, 'export', 'Use one of'),
        ({'mode': 'import'}, 'import', 'Use one of'),
        ({'mode': 'bogus'}, 'bogus', 'Use one of'),
        ({'responseFormat': 'bogus'}, 'bogus', 'Use one of'),
        ({'minFrequency': 'abc'}, 'abc', 'integer'),
        ({'minFrequency': 1.5}, '1.5', 'integer'),
        ({'top': 0}, 'top', '1..20'),
        ({'limit': 101}, 'limit', '1..100'),
        ({'offset': -1}, 'offset', 'integer'),
        ({'mode': 'compare'}, 'baseId', 'mode=list'),
        ({'mode': 'callers'}, 'methodFilter', 'called method'),
        ({'measurementId': 'no-such-measurement'}, 'no-such-measurement', 'mode=list'),
    ]:
        result = call('get_profiling_results', arguments)
        error = assert_error(result, 'invalid profiling arguments')
        assert_error_quality(error, names=[bad], suggests=[hint])
    assert_no_diff('profiling validation is read-only')
