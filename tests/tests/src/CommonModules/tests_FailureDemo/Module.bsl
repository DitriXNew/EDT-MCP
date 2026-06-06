// tests_FailureDemo — a DELIBERATELY-failing YAXUnit suite whose only purpose is to
// prove that run_yaxunit_tests reports FAILURES (not just passes) from a real run.
//
// It is kept in its OWN module so the green demo suite (tests_SampleTests) stays
// all-green: the e2e fail-count round-trip targets THIS module explicitly
// (run_yaxunit_tests modules=tests_FailureDemo) and asserts exactly one pass + one
// failure. A default extension-wide run (no filter) WILL include the one expected
// failure here — that is intentional (it demonstrates that failures surface with an
// accurate Failed count), NOT a real defect. Target tests_SampleTests for a green run.
//
// Like the other test modules it is discovered via the exported ИсполняемыеСценарии()
// entry point; the ЮТест / ЮТТесты globals resolve at run time from the loaded
// YAxUnit.cfe engine (EDT's "Variable ЮТест is not defined" warning is expected).

#Region Public

Процедура ИсполняемыеСценарии() Экспорт

	ЮТТесты.ДобавитьТестовыйНабор("FailureReporting")
		.ДобавитьТест("ReportingDemoPasses")
		.ДобавитьТест("ReportingDemoFails");

КонецПроцедуры

// A trivially-passing case so a single run yields BOTH a pass and a failure — this
// proves the counts are independent (Passed=1, Failed=1), not a blanket "all failed".
Процедура ReportingDemoPasses() Экспорт
	ЮТест.ОжидаетЧто(1).Равно(1);
КонецПроцедуры

// DELIBERATE failure: asserts 1 == 2 so the run produces exactly one failed test.
// This is how the e2e suite verifies run_yaxunit_tests surfaces failures with an
// accurate Failed count. Do NOT "fix" this assertion — the failure is the fixture.
Процедура ReportingDemoFails() Экспорт
	ЮТест.ОжидаетЧто(1).Равно(2, "intentional failure: validates failure reporting");
КонецПроцедуры

#EndRegion
