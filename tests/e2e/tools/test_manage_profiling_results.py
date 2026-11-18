"""Profiling exchange uses its own write permission and real EDT fixtures."""
import csv
import io
import re
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET
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


@e2e_test(tool='manage_profiling_results', kind='action')
def test_profiling_import_reimport_and_exports():
    _seed()
    repeated = call('manage_profiling_results', {'mode': 'import', 'importPath': str(_FIXTURE)})
    assert_ok(repeated, 'repeat import')
    assert_contains(repeated.text, 'Added: 0; skipped (UUID already loaded): 2', 'repeat import deduplicates')
    fixture_xml = ET.parse(_FIXTURE / _BASE / 'results_info.xml')
    expected_rows = len(fixture_xml.findall('.//lineInfo'))
    with tempfile.TemporaryDirectory(prefix='edt-mcp-profiling-') as directory:
        root = Path(directory)
        csv_path = root / 'measurement.csv'
        exported = call('manage_profiling_results', {'mode': 'export', 'measurementId': _BASE,
                        'exportPath': str(csv_path)})
        assert_ok(exported, 'complete CSV export')
        data = csv_path.read_bytes()
        assert data.startswith(b'\xef\xbb\xbf'), 'CSV must have UTF-8 BOM'
        rows = list(csv.reader(io.StringIO(data.decode('utf-8-sig'), newline='')))
        assert len(rows) == expected_rows + 1, (len(rows), expected_rows)
        assert rows[0] == ['measurementId', 'module', 'method', 'line', 'calls', 'timeMs', 'pureTimeMs',
                           'percent', 'purePercent', 'serverCall', 'targetTimesMs', 'targetPureTimesMs', 'source']
        assert {row[0] for row in rows[1:]} == {_BASE}
        for row in rows[1:]:
            assert re.fullmatch(r'-?\d+\.\d{3}', row[5]), row
            assert re.fullmatch(r'-?\d+\.\d{3}', row[6]), row
            assert re.fullmatch(r'-?\d+\.\d{2}', row[7]), row
            assert re.fullmatch(r'-?\d+\.\d{2}', row[8]), row
        assert any('tests_SampleTests' in row[1] for row in rows[1:])
        native = root / 'native'
        exported = call('manage_profiling_results', {'mode': 'export', 'measurementId': _BASE,
                        'exportPath': str(native), 'exportFormat': 'edt'})
        assert_ok(exported, 'native EDT export')
        target = native / _BASE
        assert (target / 'results_info.xml').is_file() and (target / 'modules_info.properties').is_file()
        assert len(ET.parse(target / 'results_info.xml').findall('.//lineInfo')) == expected_rows
        for path, fmt in ((csv_path, 'csv'), (native, 'edt')):
            refused = call('manage_profiling_results', {'mode': 'export', 'measurementId': _BASE,
                           'exportPath': str(path), 'exportFormat': fmt})
            error = assert_error(refused, 'refuse overwrite')
            assert_error_quality(error, names=['already exists'], suggests=['Choose'])
        assert csv_path.read_bytes() == data, 'refusal must preserve existing CSV bytes'
    assert_no_diff('profiling exchange must not change project sources')


@e2e_test(tool='manage_profiling_results', kind='action')
def test_profiling_exchange_validation():
    for arguments, bad, hint in [
        ({'mode': 'bogus'}, 'bogus', "must be one of 'export', 'import'"),
        ({'mode': 'summary'}, 'summary', "must be one of 'export', 'import'"),
        ({'exportFormat': 'bogus'}, 'bogus', 'must be one of'),
        ({'mode': 'export', 'exportPath': 'relative.csv'}, 'relative.csv', 'absolute'),
        ({'mode': 'import', 'importPath': 'relative'}, 'relative', 'absolute'),
        ({'mode': 'export'}, 'exportPath', 'absolute'),
        ({'mode': 'import'}, 'importPath', 'absolute'),
    ]:
        error = assert_error(call('manage_profiling_results', arguments), 'invalid profiling exchange arguments')
        assert_error_quality(error, names=[bad], suggests=[hint])
    assert_no_diff('profiling exchange validation preserves project sources')


@e2e_test(tool='manage_profiling_results', kind='action')
def test_profiling_missing_parent_and_import_directory_refusals():
    _seed()
    with tempfile.TemporaryDirectory(prefix='edt-mcp-profiling-refusal-') as directory:
        parent = Path(directory)
        missing = parent / 'missing' / 'result.csv'
        error = assert_error(call('manage_profiling_results', {'mode': 'export', 'measurementId': _BASE,
                             'exportPath': str(missing)}), 'missing parent')
        assert_error_quality(error, names=[str(missing)], suggests=['Create'])
        assert not missing.parent.exists(), 'CSV must not create missing parents'
        for path in (parent / 'missing', parent):
            error = assert_error(call('manage_profiling_results', {'mode': 'import', 'importPath': str(path)}), 'invalid import directory')
            assert_error_quality(error, names=[str(path)], suggests=['Supply'])
    assert_no_diff('refusals leave project sources alone')
