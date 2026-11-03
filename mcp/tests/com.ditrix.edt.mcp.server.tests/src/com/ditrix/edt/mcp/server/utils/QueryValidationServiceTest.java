package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.mockito.Mockito.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.eclipse.xtext.diagnostics.Severity;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.ql.model.QlFactory;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTable;
import com._1c.g5.v8.dt.ql.typesystem.IDynamicDbViewFieldComputer;

public class QueryValidationServiceTest
{
    @Test
    public void nativeResolvedFieldErrorRemainsInvalid()
    {
        XtextResource resource = resource();
        QuerySchemaTable source = source("Catalog.Catalog");
        resource.getContents().add(source);
        var computer = mock(IDynamicDbViewFieldComputer.class);
        when(computer.computeDbView(source.getTable())).thenReturn(DbViewFactory.eINSTANCE.createDbViewTableDef());
        when(computer.computeFieldsBySemanticObject(source.getTable()))
            .thenReturn(List.of(DbViewFactory.eINSTANCE.createDbViewFieldFieldDef()));
        var result = QueryValidationService.inspect(resource,
            validator(issue(source.getTable(), "Field not found")), computer, false);
        assertEquals(QueryValidationService.State.INVALID, result.state());
        assertTrue(result.complete());
        assertTrue(result.toJson().contains("\"errorCount\":1"));
    }

    @Test
    public void incompleteFieldComputationDoesNotDowngradeAProvenLookupError()
    {
        XtextResource resource = resource();
        QuerySchemaTable source = source("Catalog.Catalog");
        resource.getContents().add(source);
        var computer = mock(IDynamicDbViewFieldComputer.class);
        when(computer.computeDbView(source.getTable())).thenReturn(DbViewFactory.eINSTANCE.createDbViewTableDef());
        when(computer.computeFieldsBySemanticObject(source.getTable())).thenReturn(List.of());
        var result = QueryValidationService.inspect(resource,
            validator(issue(source.getTable(), "Field not found")), computer, false);
        assertEquals(QueryValidationService.State.INVALID, result.state());
        assertFalse(result.complete());
    }

    @Test
    public void externalTableParametersCannotBeCertifiedWithoutAFieldSchema()
    {
        XtextResource resource = resource();
        var source = QlFactory.eINSTANCE.createQuerySchemaTempTableDescription();
        var parameter = QlFactory.eINSTANCE.createParameterExpression();
        parameter.setName("Table");
        source.setTable(parameter);
        resource.getContents().add(source);
        var result = QueryValidationService.inspect(resource, validator(),
            mock(IDynamicDbViewFieldComputer.class), false);
        assertEquals(QueryValidationService.State.PARTIAL, result.state());
        assertTrue(result.toJson().contains("statically known field schema"));
    }

    @Test
    public void subqueryUsesTheNativeDerivedProjection()
    {
        XtextResource resource = resource();
        var source = QlFactory.eINSTANCE.createQuerySchemaNestedQuery();
        var query = QlFactory.eINSTANCE.createQuerySchemaSelectQuery();
        var view = QlFactory.eINSTANCE.createDbViewFromQuery();
        var field = DbViewFactory.eINSTANCE.createDbViewFieldFieldDef();
        field.setName("Amount");
        view.getFields().add(field);
        query.setDbViewByThis(view);
        source.setQuery(query);
        resource.getContents().add(source);
        var result = QueryValidationService.inspect(resource, validator(),
            mock(IDynamicDbViewFieldComputer.class), false);
        assertEquals(QueryValidationService.State.VALID, result.state());
        assertTrue(result.toJson().contains("Amount"));
    }

    @Test
    public void nativeSourceCensusUsesComputedFieldsInBothLanguages()
    {
        for (String name : List.of("AccumulationRegister.Stock.Balance", "РегистрНакопления.Stock.Остатки",
            "Document.Order.Items", "Документ.Order.Items"))
        {
            XtextResource resource = resource();
            QuerySchemaTable source = source(name);
            resource.getContents().add(source);
            var computer = mock(IDynamicDbViewFieldComputer.class);
            var view = DbViewFactory.eINSTANCE.createDbViewSelectDef();
            var field = DbViewFactory.eINSTANCE.createDbViewFieldFieldDef();
            field.setName("QuantityBalance");
            field.setNameRu("QuantityОстаток");
            when(computer.computeDbView(source.getTable())).thenReturn(view);
            when(computer.computeFieldsBySemanticObject(source.getTable())).thenReturn(List.of(field));
            var result = QueryValidationService.inspect(resource, validator(), computer, false);
            assertEquals(QueryValidationService.State.VALID, result.state());
            assertTrue(result.complete());
            assertTrue(result.toJson().contains("QuantityBalance"));
            assertTrue(result.toJson().contains("QuantityОстаток"));
            verify(computer).computeFieldsBySemanticObject(source.getTable());
        }
    }

    @Test
    public void unresolvedNativeSourceIsPartialAndRetainsLookupDiagnostic()
    {
        XtextResource resource = resource();
        QuerySchemaTable source = source("Catalog.Future");
        resource.getContents().add(source);
        var computer = mock(IDynamicDbViewFieldComputer.class);
        when(computer.computeDbView(source.getTable())).thenReturn(QlFactory.eINSTANCE.createUnresolvedDbView());
        var result = QueryValidationService.inspect(resource,
            validator(issue(source.getTable(), "Table not found")), computer, true);
        assertEquals(QueryValidationService.State.PARTIAL, result.state());
        assertFalse(result.complete());
        assertTrue(result.toJson().contains("\"errorCount\":0"));
        assertTrue(result.toJson().contains("Table not found"));
        assertTrue(result.toJson().contains("\"validationIncomplete\":true"));
        assertTrue(result.toJson().contains("Catalog.Future"));
    }

    @Test
    public void provenErrorWinsOverUnresolvedSource()
    {
        var unresolved = new QueryValidationService.Source(null, "Catalog.Future", "not resolved", List.of(), null);
        var result = new QueryValidationService.Result(false,
            List.of(problem("ERROR", "Invalid field on existing table", 1, 1, 0)), List.of(unresolved), null);
        assertEquals(QueryValidationService.State.INVALID, result.state());
        assertFalse(result.complete());
    }

    @Test
    public void failedNativeComputationNeverBecomesValid()
    {
        XtextResource resource = resource();
        QuerySchemaTable source = source("Document.Order.Items");
        resource.getContents().add(source);
        var computer = mock(IDynamicDbViewFieldComputer.class);
        when(computer.computeDbView(source.getTable())).thenThrow(new IllegalStateException("index unavailable"));
        var result = QueryValidationService.inspect(resource, validator(), computer, false);
        assertEquals(QueryValidationService.State.PARTIAL, result.state());
        assertTrue(result.toJson().contains("index unavailable"));
    }

    @Test
    public void qualifiedFutureFieldIsPartialButIndependentFieldErrorRemainsInvalid()
    {
        var operator = QlFactory.eINSTANCE.createQuerySchemaOperator();
        var source = source("Future");
        source.setAlias("F");
        var from = QlFactory.eINSTANCE.createQuerySchemaSource();
        from.setSource(source);
        operator.getSources().add(from);
        var field = QlFactory.eINSTANCE.createMultiPartCommonExpression();
        var alias = QlFactory.eINSTANCE.createSinglePartCommonExpression();
        alias.setContent("F");
        field.setSourceTable(alias);
        field.setContent("Ref");
        var select = QlFactory.eINSTANCE.createQuerySchemaExpression();
        select.setExpression(field);
        operator.getSelectFields().add(select);
        var census = List.of(new QueryValidationService.Source(source, "Future", "not resolved", List.of(), null));
        assertTrue(QueryValidationService.dependsOnUnresolvedSource(field, "Field not found", census));
        assertFalse(QueryValidationService.dependsOnUnresolvedSource(field, "Syntax error", census));
        alias.setContent("Existing");
        assertFalse(QueryValidationService.dependsOnUnresolvedSource(field, "Field not found", census));
    }

    @Test
    public void unavailableValidatorIsExplicitAndCannotAuthorizeAWrite()
    {
        var result = QueryValidationService.Result.unavailable(false, "Install QL support");
        assertEquals(QueryValidationService.State.PARTIAL, result.state());
        assertFalse(result.available());
        assertTrue(result.toJson().contains("\"success\":false"));
    }

    private static XtextResource resource()
    {
        XtextResource resource = new XtextResource();
        resource.setURI(URI.createURI("test:/query.qldcs"));
        return resource;
    }

    private static QuerySchemaTable source(String name)
    {
        var table = QlFactory.eINSTANCE.createSinglePartQuerySchemaTable();
        table.setTableName(name);
        var source = QlFactory.eINSTANCE.createQuerySchemaTable();
        source.setTable(table);
        return source;
    }

    private static IResourceValidator validator(Issue... issues)
    {
        var validator = mock(IResourceValidator.class);
        when(validator.validate(any(), any(), any())).thenReturn(List.of(issues));
        return validator;
    }

    private static Issue issue(org.eclipse.emf.ecore.EObject object, String code)
    {
        var issue = mock(Issue.class);
        when(issue.getSeverity()).thenReturn(Severity.ERROR);
        when(issue.getCode()).thenReturn(code);
        when(issue.getMessage()).thenReturn(code);
        when(issue.getUriToProblem()).thenReturn(EcoreUtil.getURI(object));
        return issue;
    }

    private static QueryValidationService.Problem problem(String severity, String message,
        int line, int column, int offset)
    {
        return new QueryValidationService.Problem(severity, message, line, column, offset,
            "validator", null, false);
    }

    private static String json(List<QueryValidationService.Problem> issues, boolean mode)
    {
        return new QueryValidationService.Result(mode, issues, List.of(), null).toJson();
    }
    // ==================== buildResult (migrated to ToolResult) ====================
    // The result JSON is built by the shared ToolResult/Gson path. Gson emits
    // compact ("key":value, no spaces) and escapes >, <, & as \\uXXXX, so the
    // assertions below use delimiter-free substrings.

    @Test
    public void testBuildResultValidWhenNoIssues()
    {
        String json = json(List.of(), false);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"issues\":[]")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultReportsError()
    {
        QueryValidationService.Problem err =
            problem("ERROR", "Field 'Foo' not found", 1, 8, 7); //$NON-NLS-1$ //$NON-NLS-2$
        String json = json(List.of(err), true);
        assertTrue(json.contains("\"success\":true")); // tool ran; query is what's invalid //$NON-NLS-1$
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"ERROR\"")); //$NON-NLS-1$
        // Message text is present; Gson HTML-escapes the apostrophes ('->\\u0027),
        // so assert on delimiter-free fragments rather than the raw message.
        assertTrue(json.contains("Foo")); //$NON-NLS-1$
        assertTrue(json.contains("not found")); //$NON-NLS-1$
        assertTrue(json.contains("\"line\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"column\":8")); //$NON-NLS-1$
        assertTrue(json.contains("\"offset\":7")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultOmitsNonPositiveLocation()
    {
        // A non-located message (line/column <= 0, offset < 0) omits those keys,
        // A warning alone leaves a complete query valid.
        QueryValidationService.Problem warn =
            problem("WARNING", "general warning", -1, -1, -1); //$NON-NLS-1$ //$NON-NLS-2$
        String json = json(List.of(warn), false);
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"WARNING\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"line\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"offset\":")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultCountsInfoSeverity()
    {
        // An INFO issue increments infoCount and leaves a complete query valid.
        QueryValidationService.Problem info =
            problem("INFO", "informational note", 3, 1, 12); //$NON-NLS-1$ //$NON-NLS-2$
        String json = json(List.of(info), false);
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"INFO\"")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultCountsMixedSeverities()
    {
        // Mixed bag: two errors, one warning, one info — each bucket counted independently.
        List<QueryValidationService.Problem> issues = Arrays.asList(
            problem("ERROR", "e1", 1, 1, 0), //$NON-NLS-1$ //$NON-NLS-2$
            problem("ERROR", "e2", 2, 1, 5), //$NON-NLS-1$ //$NON-NLS-2$
            problem("WARNING", "w1", 3, 1, 9), //$NON-NLS-1$ //$NON-NLS-2$
            problem("INFO", "i1", 4, 1, 13)); //$NON-NLS-1$ //$NON-NLS-2$
        String json = json(issues, false);
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":2")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":1")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultEmptyWithDcsModeTrue()
    {
        // dcsMode is echoed verbatim independent of the (empty) issue list.
        String json = json(List.of(), true);
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"issues\":[]")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultLineOnlyEmitsLineNotColumnNorOffset()
    {
        // line > 0 but column <= 0 and offset < 0: only the "line" key is emitted.
        QueryValidationService.Problem issue =
            problem("ERROR", "located by line only", 42, 0, -1); //$NON-NLS-1$ //$NON-NLS-2$
        String json = json(List.of(issue), false);
        assertTrue(json.contains("\"line\":42")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"offset\":")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultOffsetZeroIsEmitted()
    {
        // offset uses a >= 0 guard (line/column use > 0): offset 0 IS a valid location and is emitted.
        QueryValidationService.Problem issue =
            problem("WARNING", "at start", -1, -1, 0); //$NON-NLS-1$ //$NON-NLS-2$
        String json = json(List.of(issue), false);
        assertTrue("offset 0 must be emitted (>=0 guard)", json.contains("\"offset\":0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.contains("\"line\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
    }

    @Test
    public void testQueryIssueFieldsArePreserved()
    {
        // The internal carrier keeps every field verbatim for buildResult to read.
        QueryValidationService.Problem issue =
            problem("ERROR", "msg", 7, 3, 21); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ERROR", issue.severity()); //$NON-NLS-1$
        assertEquals("msg", issue.message()); //$NON-NLS-1$
        assertEquals(7, issue.line());
        assertEquals(3, issue.column());
        assertEquals(21, issue.offset());
    }
}
