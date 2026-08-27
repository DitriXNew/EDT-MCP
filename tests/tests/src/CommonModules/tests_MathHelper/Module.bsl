// Helper common module added live to demonstrate the "new module -> run" loop.
// A server CommonModule of the tests extension, called from the YAXUnit example
// tests (tests_SampleTests.MathHelperSubtracts) to prove a freshly-added module
// is published into the infobase and callable at run time.

#Region Public

// Returns Minuend - Subtrahend.
Функция Subtract(Minuend, Subtrahend) Экспорт
	Возврат Minuend - Subtrahend;
КонецФункции

// Fixture for find_references: an extension BSL usage of the ADOPTED Catalog.
// find_references on the BASE Catalog.Catalog must surface this module, because an
// adopted object is its own EObject and extension code resolves to that copy.
Функция AdoptedCatalogManagerForReferenceSearch() Экспорт
	Возврат Catalogs.Catalog;
КонецФункции

#EndRegion
