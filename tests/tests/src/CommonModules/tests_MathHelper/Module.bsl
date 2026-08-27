// Helper common module added live to demonstrate the "new module -> run" loop.
// A server CommonModule of the tests extension, called from the YAXUnit example
// tests (tests_SampleTests.MathHelperSubtracts) to prove a freshly-added module
// is published into the infobase and callable at run time.

#Region Public

// Returns Minuend - Subtrahend.
Функция Subtract(Minuend, Subtrahend) Экспорт
	Возврат Minuend - Subtrahend;
КонецФункции

#EndRegion
