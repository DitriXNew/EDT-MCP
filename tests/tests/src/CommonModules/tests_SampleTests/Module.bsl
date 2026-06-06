// Sample YAXUnit test suite for TestConfiguration.
//
// This module lives in the "tests" configuration extension (namePrefix tests_)
// and is discovered by YAXUnit because it exports ИсполняемыеСценарии(). The
// YAXUnit engine extension (YAxUnit.cfe) must be loaded in the infobase for the
// ЮТест / ЮТТесты globals to resolve at run time — EDT shows "Variable ЮТест is
// not defined" because the engine is not in the EDT workspace; that warning is
// expected and harmless (it resolves at run time from the loaded .cfe).
//
// Run it headlessly via the MCP tool:
//   run_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client")
// (no filter needed: the default filter.extensions=["tests"] matches this
// extension's name). See the edt-mcp-yaxunit skill for the full workflow.
//
// Every method is Экспорт: YAXUnit invokes the registration entry point and each
// test by name across the module boundary, so they must be exported (and thus
// live in the Public region).

#Region Public

// YAXUnit calls this exported procedure to collect the module's tests. Each
// .ДобавитьТест("MethodName") registers an exported procedure below as a test.
Процедура ИсполняемыеСценарии() Экспорт

	ЮТТесты.ДобавитьТестовыйНабор("Arithmetic")
		.ДобавитьТест("TwoPlusTwoIsFour")
		.ДобавитьТест("CallsBaseConfigCalcModule")
		.ДобавитьТест("SubtractionWorks");

	ЮТТесты.ДобавитьТестовыйНабор("Strings")
		.ДобавитьТест("StringConcatenation");

	// One test method, several parameter sets — each set is a separate test case.
	ЮТТесты.ДобавитьТестовыйНабор("Parameterized")
		.ДобавитьТест("SumByParameters")
			.СПараметрами(2, 3, 5)
			.СПараметрами(0, 0, 0)
			.СПараметрами(-1, 1, 0);

КонецПроцедуры

// Optional hook — YAXUnit runs it before each test in this module. Per-test
// scratch state goes into ЮТест.КонтекстТеста() (a Структура living for one test).
Процедура ПередКаждымТестом() Экспорт
	ЮТест.КонтекстТеста().Вставить("Started", Истина);
КонецПроцедуры

// Smallest possible assertion: value-equality.
Процедура TwoPlusTwoIsFour() Экспорт
	ЮТест.ОжидаетЧто(2 + 2).Равно(4);
КонецПроцедуры

// Added live to exercise the change -> run loop (subtraction).
Процедура SubtractionWorks() Экспорт
	ЮТест.ОжидаетЧто(10 - 3).Равно(7);
КонецПроцедуры

// A test in the extension can call code from the BASE configuration. Calc is a
// server CommonModule of TestConfiguration; this proves cross-config visibility.
Процедура CallsBaseConfigCalcModule() Экспорт
	ЮТест.ОжидаетЧто(Calc.Add(2, 3)).Равно(5, "Calc.Add(2,3) must return 5");
КонецПроцедуры

// Chained string assertions on a single value.
Процедура StringConcatenation() Экспорт
	Value = "Hello" + ", " + "world";
	ЮТест.ОжидаетЧто(Value)
		.Равно("Hello, world")
		.Заполнено()
		.НачинаетсяС("Hello")
		.ЗаканчиваетсяНа("world")
		.ИмеетДлину(12)
		.НеСодержит("foo");
КонецПроцедуры

// Parameterized test: invoked once per .СПараметрами(...) set registered above.
//
// Параметры:
//   First - Число - первое слагаемое.
//   Second - Число - второе слагаемое.
//   Expected - Число - ожидаемая сумма First + Second.
Процедура SumByParameters(First, Second, Expected) Экспорт
	ЮТест.ОжидаетЧто(First + Second).Равно(Expected, "" + First + " + " + Second + " must be " + Expected);
КонецПроцедуры

#EndRegion
