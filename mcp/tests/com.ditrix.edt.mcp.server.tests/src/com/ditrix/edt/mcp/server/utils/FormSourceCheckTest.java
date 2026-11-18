/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.emf.ecore.xmi.FeatureNotFoundException;
import org.eclipse.emf.ecore.xmi.IllegalValueException;
import org.junit.Test;

import com._1c.g5.modeling.xml.XmlException;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.LabelDecorationExtInfo;

/**
 * How {@link FormSourceCheck} words what EDT's {@code .form} parser reported, and how
 * {@link FormModelValidator#sourceFindings} turns it into findings. The diagnostics are built the
 * way EDT's parser builds them: its own wrapper around the EMF exception, which carries the location.
 */
public class FormSourceCheckTest
{
    private static final String FILE = "src/Catalogs/Goods/Forms/ItemForm/Form.form"; //$NON-NLS-1$

    /** An extended tooltip's ext-info inside a named item, as the parser holds it when the value fails. */
    private static LabelDecorationExtInfo tooltipExtInfo()
    {
        Decoration tooltip = FormFactory.eINSTANCE.createDecoration();
        tooltip.setName("Decoration1ExtendedTooltip"); //$NON-NLS-1$
        LabelDecorationExtInfo extInfo = FormFactory.eINSTANCE.createLabelDecorationExtInfo();
        tooltip.setExtInfo(extInfo);
        return extInfo;
    }

    private static XmlException invalidLiteral(String value, int line, int column)
    {
        return new XmlException(new IllegalValueException(tooltipExtInfo(),
            FormPackage.Literals.LABEL_DECORATION_EXT_INFO__HORIZONTAL_ALIGN, value,
            new IllegalArgumentException("The value '" + value + "' is not a valid enumerator"), //$NON-NLS-1$ //$NON-NLS-2$
            FILE, line, column));
    }

    @Test
    public void testAnUnknownEnumLiteralNamesTheValueTheFeatureTheAllowedLiteralsAndTheDefault()
    {
        FormSourceCheck.Problem problem = FormSourceCheck.problemOf(invalidLiteral("Leftmost", 21, 9), true, FILE); //$NON-NLS-1$

        assertEquals(FormSourceCheck.Kind.INVALID_VALUE, problem.kind());
        assertEquals("the location is taken from the wrapped parser exception", //$NON-NLS-1$
            FILE + ":21:9", problem.location()); //$NON-NLS-1$
        String message = problem.message();
        assertTrue(message, message.startsWith("'Leftmost' is not a valid horizontalAlign of LabelDecorationExtInfo")); //$NON-NLS-1$
        assertTrue("the nearest named container identifies the item: " + message, //$NON-NLS-1$
            message.contains("in 'Decoration1ExtendedTooltip'")); //$NON-NLS-1$
        assertTrue("every allowed literal is listed: " + message, //$NON-NLS-1$
            message.contains("allowed: Auto, Left, Center, Right")); //$NON-NLS-1$
        assertTrue("the default EDT loaded instead is named: " + message, //$NON-NLS-1$
            message.contains("EDT loaded the default (Auto) instead")); //$NON-NLS-1$
        assertTrue("and the consequence of a re-save: " + message, //$NON-NLS-1$
            message.contains("re-save of this form drops the value")); //$NON-NLS-1$
    }

    @Test
    public void testAnotherLoadErrorIsUnreadableContentWithTheParsersOwnMessage()
    {
        XmlException unknown = new XmlException(new FeatureNotFoundException("childrenAlignment", //$NON-NLS-1$
            tooltipExtInfo(), FILE, 30, 5));

        FormSourceCheck.Problem problem = FormSourceCheck.problemOf(unknown, true, FILE);

        assertEquals(FormSourceCheck.Kind.UNREADABLE, problem.kind());
        assertEquals(FILE + ":30:5", problem.location()); //$NON-NLS-1$
        assertTrue(problem.message(), problem.message().contains("childrenAlignment")); //$NON-NLS-1$
        assertTrue(problem.message(), problem.message().contains("not in the model")); //$NON-NLS-1$
        assertFalse("no doubled period between the parser's text and ours: " + problem.message(), //$NON-NLS-1$
            problem.message().contains(".."));
    }

    @Test
    public void testAWarningStaysAWarningEvenForARejectedValue()
    {
        FormSourceCheck.Problem problem = FormSourceCheck.problemOf(invalidLiteral("Leftmost", 3, 1), false, FILE); //$NON-NLS-1$

        assertEquals(FormSourceCheck.Kind.WARNING, problem.kind());
    }

    @Test
    public void testADiagnosticWithoutALineIsLocatedByTheFileAlone()
    {
        FormSourceCheck.Problem problem = FormSourceCheck.problemOf(invalidLiteral("Leftmost", 0, 0), true, FILE); //$NON-NLS-1$

        assertEquals(FILE, problem.location());
    }

    @Test
    public void testEachKindBecomesItsOwnCodeAndSeverity()
    {
        List<FormModelValidator.Finding> findings = FormModelValidator.sourceFindings(List.of(
            new FormSourceCheck.Problem(FormSourceCheck.Kind.INVALID_VALUE, FILE + ":1:1", "a"), //$NON-NLS-1$ //$NON-NLS-2$
            new FormSourceCheck.Problem(FormSourceCheck.Kind.UNREADABLE, FILE + ":2:1", "b"), //$NON-NLS-1$ //$NON-NLS-2$
            new FormSourceCheck.Problem(FormSourceCheck.Kind.WARNING, FILE + ":3:1", "c"), //$NON-NLS-1$ //$NON-NLS-2$
            new FormSourceCheck.Problem(FormSourceCheck.Kind.NOT_CHECKED, FILE, "d"))); //$NON-NLS-1$

        assertEquals(4, findings.size());
        assertFinding(findings.get(0), FormModelValidator.SEVERITY_ERROR,
            FormModelValidator.CODE_INVALID_SOURCE_VALUE, FILE + ":1:1", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFinding(findings.get(1), FormModelValidator.SEVERITY_ERROR,
            FormModelValidator.CODE_UNREADABLE_SOURCE_CONTENT, FILE + ":2:1", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFinding(findings.get(2), FormModelValidator.SEVERITY_WARNING,
            FormModelValidator.CODE_SOURCE_LOAD_WARNING, FILE + ":3:1", "c"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFinding(findings.get(3), FormModelValidator.SEVERITY_WARNING,
            FormModelValidator.CODE_SOURCE_NOT_CHECKED, FILE, "d"); //$NON-NLS-1$
    }

    @Test
    public void testAMissingSourcePathIsReportedAsNotCheckedInsteadOfFailing()
    {
        List<FormSourceCheck.Problem> problems = FormSourceCheck.check(null, null, null, null);

        assertEquals(1, problems.size());
        assertEquals(FormSourceCheck.Kind.NOT_CHECKED, problems.get(0).kind());
        assertTrue(problems.get(0).message(), problems.get(0).message().contains("not checked")); //$NON-NLS-1$
    }

    private static void assertFinding(FormModelValidator.Finding finding, String severity, String code,
        String path, String message)
    {
        assertEquals(severity, finding.severity);
        assertEquals(code, finding.code);
        assertEquals(path, finding.path);
        assertEquals(message, finding.message);
    }

    /** EDT's form resource clears its error list once a parse ends; the recorder keeps what was added. */
    @Test
    public void testTheRecorderKeepsDiagnosticsTheResourceClearsAfterTheParse()
    {
        org.eclipse.emf.ecore.resource.Resource resource = new org.eclipse.emf.ecore.resource.impl.ResourceImpl();
        List<org.eclipse.emf.ecore.resource.Resource.Diagnostic> errors = new java.util.ArrayList<>();
        List<org.eclipse.emf.ecore.resource.Resource.Diagnostic> warnings = new java.util.ArrayList<>();
        resource.eAdapters().add(new FormSourceCheck.DiagnosticRecorder(errors, warnings));
        XmlException error = new XmlException(new IllegalValueException(null, null, "Leftmost", null, "f", 3, 4));
        XmlException warning = new XmlException(new IllegalValueException(null, null, "x", null, "f", 5, 6));
        resource.getErrors().add(error);
        resource.getWarnings().addAll(List.of(warning));
        resource.getErrors().clear();
        resource.getWarnings().clear();
        assertEquals(List.of(error), errors);
        assertEquals(List.of(warning), warnings);
    }
}
