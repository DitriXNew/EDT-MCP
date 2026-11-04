/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.symbol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.xtext.ui.editor.hover.AnnotationWithQuickFixesHover.AnnotationInfo;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

/**
 * Tests for {@link SymbolInfoService}.
 * <p>
 * Two layers:
 * <ul>
 * <li>{@link SymbolInfoService#meaningfulOrNull(String)} — the pure string guard that keeps
 * get_symbol_info from leaking a bare {@code Object.toString()} reference when a hover (e.g. an
 * annotation/quick-fix hover at a validation-marked position) has no extractable text.</li>
 * <li>{@link SymbolInfoService#buildEObjectInfo(org.eclipse.emf.ecore.EObject)} — the per-kind
 * Markdown-table formatter. It is driven here with in-memory {@code BslFactory}-built BSL model
 * objects (no live workbench, editor, Xtext resource or injector). Every branch exercised below
 * reads only EMF getters on the constructed object; {@code getStartLine}/{@code getEndLine}
 * return 0 for an object with no node model, so the "Lines" row is simply omitted — no exception.
 * The live editor/hover path of {@code getSymbolInfo} needs a real workbench and is e2e-covered.</li>
 * </ul>
 * Same-package access reaches the package-private {@code buildEObjectInfo}, matching the seam
 * convention used by {@code AbstractMetadataFormatterTest}.
 */
public class SymbolInfoServiceTest
{
    private final SymbolInfoService service = new SymbolInfoService();

    private static final String OBJECT_REFERENCE =
        "com._1c.g5.v8.dt.bsl.ui.hover.BslAnnotationWithQuickFixesHover$BslAnnotationInfo@13b1c2f5";
    private static final String SSH_DESCRIPTION =
        "Connect(Repository): Clones ssh://git@db/repo into the workspace.";

    public interface HoverViewer extends ISourceViewer
    {
        ITextHover getTextHover(int offset, int stateMask);
    }

    public interface RichHover extends ITextHover, ITextHoverExtension2
    {
    }

    public static class HtmlHover
    {
        private final String html;

        public HtmlHover()
        {
            this(OBJECT_REFERENCE);
        }

        public HtmlHover(String html)
        {
            this.html = html;
        }

        public String getHtml()
        {
            return html;
        }
    }

    public static class InputHover
    {
        public Object getInputElement()
        {
            return OBJECT_REFERENCE;
        }
    }

    public static class ReferenceHover
    {
        @Override
        public String toString()
        {
            return OBJECT_REFERENCE;
        }
    }

    public static class BrokenHtmlHover
    {
        public String getHtml()
        {
            throw new IllegalStateException("Changed hover API");
        }
    }

    public static class BrokenHtmlWithInput extends BrokenHtmlHover
    {
        public Object getInputElement()
        {
            return "Recovered input text";
        }
    }

    public static class BrokenInputWithText
    {
        public Object getInputElement()
        {
            throw new IllegalStateException("Changed input API");
        }

        @Override
        public String toString()
        {
            return "Useful object description";
        }
    }

    public static class BrokenGettersHover extends BrokenHtmlHover
    {
        public Object getInputElement()
        {
            throw new IllegalStateException("Changed input API");
        }

        @Override
        public String toString()
        {
            return OBJECT_REFERENCE;
        }
    }

    public static class BrokenAnnotationInfo extends AnnotationInfo
    {
        public final Object annotation = "Changed annotation API";

        public BrokenAnnotationInfo()
        {
            super(null, new Position(8, 6), null, new ICompletionProposal[0]);
        }
    }

    private String hover(Object richInfo, String basicInfo, IXtextDocument document) throws Exception
    {
        HoverViewer viewer = mock(HoverViewer.class);
        RichHover hover = mock(RichHover.class);
        Region region = new Region(8, 6);
        when(viewer.getTextHover(8, 0)).thenReturn(hover);
        when(viewer.getDocument()).thenReturn(document);
        when(hover.getHoverRegion(viewer, 8)).thenReturn(region);
        when(hover.getHoverInfo2(viewer, region)).thenReturn(richInfo);
        when(hover.getHoverInfo(viewer, region)).thenReturn(basicInfo);
        java.lang.reflect.Method method =
            SymbolInfoService.class.getDeclaredMethod("tryGetHoverInfo", ISourceViewer.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(service, viewer, 8);
    }

    private String hoverWithWarnings(Object richInfo, String basicInfo, String... causes) throws Exception
    {
        List<IStatus> recorded = new ArrayList<>();
        ILog log = Platform.getLog(FrameworkUtil.getBundle(SymbolInfoService.class));
        ILogListener listener = (status, plugin) -> recorded.add(status);
        log.addLogListener(listener);
        try
        {
            String result = hover(richInfo, basicInfo, null);
            assertEquals("one warning per failing getter", causes.length, recorded.size());
            for (int i = 0; i < causes.length; i++)
            {
                assertEquals(IStatus.WARNING, recorded.get(i).getSeverity());
                assertTrue(recorded.get(i).getMessage(), recorded.get(i).getMessage().contains(causes[i]));
            }
            return result;
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }

    @Test
    public void testSshDescriptionSurvivesRawAndBasicHover() throws Exception
    {
        assertEquals(SSH_DESCRIPTION, hover(SSH_DESCRIPTION, null, null));
        assertEquals(SSH_DESCRIPTION, hover(null, SSH_DESCRIPTION, null));
    }

    @Test
    public void testSshDescriptionSurvivesHtmlHover() throws Exception
    {
        assertEquals(SSH_DESCRIPTION, hover(new HtmlHover("<p>" + SSH_DESCRIPTION + "</p>"), null, null));
    }

    @Test
    public void testSshDescriptionSurvivesAnnotationMessage() throws Exception
    {
        AnnotationInfo info = new AnnotationInfo(new Annotation("check", false, SSH_DESCRIPTION),
            new Position(8, 6), null, new ICompletionProposal[0]);
        assertEquals("### Annotation\n\n" + SSH_DESCRIPTION, hover(info, null, null));
    }

    @Test
    public void testHtmlWrappedIdentityIsDroppedAfterConversion() throws Exception
    {
        assertNull(hover(new HtmlHover("<p>" + OBJECT_REFERENCE + "</p>"), null, null));
        assertNull(hover(null, "<p>" + OBJECT_REFERENCE + "</p>", null));
    }

    @Test
    public void testPaddedAndEscapedBareIdentitiesAreDropped()
    {
        assertNull(SymbolInfoService.meaningfulOrNull(" \t" + OBJECT_REFERENCE + "\r\n"));
        assertNull(SymbolInfoService.meaningfulOrNull(" \t" + OBJECT_REFERENCE.replace("_", "\\_") + "\r\n"));
    }

    @Test
    public void testFailingHtmlGetterStillUsesInputElement() throws Exception
    {
        assertEquals("Recovered input text",
            hoverWithWarnings(new BrokenHtmlWithInput(), null, "Changed hover API"));
    }

    @Test
    public void testFailingInputGetterStillUsesToString() throws Exception
    {
        assertEquals("Useful object description",
            hoverWithWarnings(new BrokenInputWithText(), null, "Changed input API"));
    }

    @Test
    public void testFailingGettersStillAllowBasicHover() throws Exception
    {
        assertEquals("Basic hover text", hoverWithWarnings(new BrokenGettersHover(), "Basic hover text",
            "Changed hover API", "Changed input API"));
    }

    @Test
    public void testAnnotationHoverReturnsMessageAndSymbol() throws Exception
    {
        AnnotationInfo info = new AnnotationInfo(new Annotation("check", false, "Missing function description"),
            new Position(8, 6), null, new ICompletionProposal[0]) {};
        IXtextDocument document = mock(IXtextDocument.class);
        when(document.readOnly(any())).thenReturn("Function Маркер() Export");
        String result = hover(info, OBJECT_REFERENCE, document);
        assertTrue(result, result.contains("Missing function description"));
        assertTrue(result, result.contains("Function Маркер() Export"));
        assertTrue(result, result.contains("Annotation"));
        assertFalse(result, result.contains("AnnotationInfo@"));
    }

    @Test
    public void testAnnotationMessageSurvivesWithoutSymbol() throws Exception
    {
        AnnotationInfo info = new AnnotationInfo(new Annotation("check", false, "Missing function description"),
            new Position(8, 6), null, new ICompletionProposal[0]);
        String result = hover(info, null, null);
        assertNotNull("annotation message survives without a symbol", result);
        assertTrue(result, result.contains("Missing function description"));
    }

    @Test
    public void testAnnotationMessageIsPlainText() throws Exception
    {
        String message = "Describe <Parameter> & return value";
        AnnotationInfo info = new AnnotationInfo(new Annotation("check", false, message),
            new Position(8, 6), null, new ICompletionProposal[0]);
        String result = hover(info, null, null);
        assertNotNull("annotation messages are plain text", result);
        assertTrue(result, result.contains(message));
    }

    @Test
    public void testStringHoverReferenceIsDropped() throws Exception
    {
        assertNull(hover(OBJECT_REFERENCE, null, null));
    }

    @Test
    public void testHtmlHoverReferenceIsDropped() throws Exception
    {
        assertNull(hover(new HtmlHover(), null, null));
    }

    @Test
    public void testBasicHoverReferenceIsDropped() throws Exception
    {
        assertNull(hover(null, OBJECT_REFERENCE, null));
    }

    @Test
    public void testEscapedHoverReferenceIsDropped() throws Exception
    {
        assertNull(hover(OBJECT_REFERENCE.replace("_", "\\_"), null, null));
        assertNull(hover(null, OBJECT_REFERENCE.replace("_", "\\_"), null));
    }

    @Test
    public void testEncodedHtmlReferenceIsDroppedAfterMarkdown() throws Exception
    {
        assertNull(hover("<p>" + OBJECT_REFERENCE.replace("@", "&#64;") + "</p>", null, null));
    }

    @Test
    public void testInputAndToStringReferenceAreDropped() throws Exception
    {
        assertNull(hover(new InputHover(), null, null));
        assertNull(hover(new ReferenceHover(), null, null));
    }

    @Test
    public void testBrokenReflectionDoesNotReturnObjectDescription() throws Exception
    {
        List<IStatus> recorded = new ArrayList<>();
        ILog log = Platform.getLog(FrameworkUtil.getBundle(SymbolInfoService.class));
        ILogListener listener = (status, plugin) -> recorded.add(status);
        log.addLogListener(listener);
        try
        {
            assertNull(hover(new BrokenHtmlHover(), null, null));
            assertEquals("one diagnostic for a failed reflective getter", 1, recorded.size());
            assertEquals(IStatus.WARNING, recorded.get(0).getSeverity());
            assertTrue(recorded.get(0).getMessage().contains("Changed hover API"));
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }

    @Test
    public void testAnnotationReflectionFailureLogsOnceAndStillResolvesSymbol() throws Exception
    {
        List<IStatus> recorded = new ArrayList<>();
        ILog log = Platform.getLog(FrameworkUtil.getBundle(SymbolInfoService.class));
        ILogListener listener = (status, plugin) -> recorded.add(status);
        IXtextDocument document = mock(IXtextDocument.class);
        when(document.readOnly(any())).thenReturn("Function Маркер() Export");
        log.addLogListener(listener);
        try
        {
            String result = hover(new BrokenAnnotationInfo(), OBJECT_REFERENCE, document);
            assertTrue(result, result.contains("Function Маркер() Export"));
            assertTrue(result, result.contains("Annotation"));
            assertFalse(result, result.contains("BslAnnotationInfo"));
            assertEquals("one diagnostic for a changed annotation API", 1, recorded.size());
            assertEquals(IStatus.WARNING, recorded.get(0).getSeverity());
            assertTrue(recorded.get(0).getMessage().contains("annotation field"));
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }

    @Test
    public void testPlainHoverUnchanged() throws Exception
    {
        IXtextDocument document = mock(IXtextDocument.class);
        assertEquals("**Function** Compute()", hover("<b>Function</b> Compute()", null, document));
        assertEquals("**Function** Compute()", hover(null, "<b>Function</b> Compute()", document));
        assertEquals("Contact john@face.book", hover("Contact john@face.book", null, document));
        verify(document, never()).readOnly(any());
    }

    // ==================== meaningfulOrNull — the pure string guard ====================

    @Test
    public void testRawObjectReferenceBecomesNull()
    {
        // The exact shape observed in the audit: a BslAnnotationInfo quick-fix hover.
        assertNull(SymbolInfoService.meaningfulOrNull(
            "com._1c.g5.v8.dt.bsl.ui.hover.BslAnnotationWithQuickFixesHover$BslAnnotationInfo@1b8ef34a"));
    }

    @Test
    public void testSimpleClassAtHashBecomesNull()
    {
        assertNull(SymbolInfoService.meaningfulOrNull("Foo@deadbeef"));
    }

    @Test
    public void testNullAndBlankBecomeNull()
    {
        assertNull(SymbolInfoService.meaningfulOrNull(null));
        assertNull(SymbolInfoService.meaningfulOrNull("   "));
    }

    @Test
    public void testRealHoverContentIsKept()
    {
        String real = "Parameter Minuend\nPassing: by reference";
        assertEquals(real, SymbolInfoService.meaningfulOrNull(real));
    }

    @Test
    public void testTextWithAtSignInProseIsKept()
    {
        // A real hover with an @ inside multi-token prose is NOT a bare ClassName@hash.
        String s = "Annotation &Вместо(\"Method\") — overrides at line 9";
        assertEquals(s, SymbolInfoService.meaningfulOrNull(s));
    }

    // ==================== cell / codeCell — the pure row builders ====================

    @Test
    public void testCellEscapesPipe()
    {
        // A '|' in the value must be escaped so it cannot break the table column.
        assertEquals("| **Type** | String \\| Number |\n",
            SymbolInfoService.cell("Type", "String | Number"));
    }

    @Test
    public void testCellNullValueRendersEmpty()
    {
        assertEquals("| **Kind** |  |\n", SymbolInfoService.cell("Kind", null));
    }

    @Test
    public void testCodeCellWrapsInBackticks()
    {
        assertEquals("| **Symbol** | `Name` |\n", SymbolInfoService.codeCell("Symbol", "Name"));
    }

    // ==================== buildEObjectInfo — per-kind formatting ====================

    @Test
    public void testProcedureInfo()
    {
        Procedure proc = BslFactory.eINSTANCE.createProcedure();
        proc.setName("DoWork");

        String info = service.buildEObjectInfo(proc);

        assertTrue("has table header", info.startsWith("| Property | Value |"));
        assertTrue("symbol row", info.contains("| **Symbol** | `DoWork` |"));
        assertTrue("kind is Procedure", info.contains("| **Kind** | Procedure |"));
        assertTrue("signature row", info.contains("| **Signature** | `Procedure DoWork(-)` |"));
        assertTrue("not exported", info.contains("| **Export** | No |"));
        // No node model on an in-memory object -> no "Lines" row.
        assertFalse("no lines row without node model", info.contains("**Lines**"));
    }

    @Test
    public void testExportedFunctionWithParameter()
    {
        Function fn = BslFactory.eINSTANCE.createFunction();
        fn.setName("Compute");
        fn.setExport(true);

        FormalParam p = BslFactory.eINSTANCE.createFormalParam();
        p.setName("Source");
        p.setByValue(true);
        fn.getFormalParams().add(p);

        String info = service.buildEObjectInfo(fn);

        assertTrue("kind is Function", info.contains("| **Kind** | Function |"));
        assertTrue("exported", info.contains("| **Export** | Yes |"));
        // ByVal parameter appears in both the signature and the Parameters row.
        assertTrue("signature includes Val param and Export",
            info.contains("| **Signature** | `Function Compute(Val Source) Export` |"));
        assertTrue("parameters row", info.contains("| **Parameters** | Val Source |"));
    }

    @Test
    public void testFormalParamInfo()
    {
        // A param contained by a method so the "In method" row is exercised too.
        Method owner = BslFactory.eINSTANCE.createProcedure();
        owner.setName("Owner");

        FormalParam p = BslFactory.eINSTANCE.createFormalParam();
        p.setName("Value");
        p.setByValue(false);
        owner.getFormalParams().add(p);

        String info = service.buildEObjectInfo(p);

        assertTrue("symbol row", info.contains("| **Symbol** | `Value` |"));
        assertTrue("kind is Parameter", info.contains("| **Kind** | Parameter |"));
        assertTrue("not by value", info.contains("| **ByValue** | No |"));
        assertTrue("containing method", info.contains("| **In method** | `Owner` |"));
    }

    @Test
    public void testStaticFeatureAccessInfo()
    {
        StaticFeatureAccess sfa = BslFactory.eINSTANCE.createStaticFeatureAccess();
        sfa.setName("MyVar");

        String info = service.buildEObjectInfo(sfa);

        assertTrue("symbol row", info.contains("| **Symbol** | `MyVar` |"));
        assertTrue("kind", info.contains("| **Kind** | StaticFeatureAccess |"));
        assertTrue("emf type row", info.contains("| **EMF type** | StaticFeatureAccess |"));
    }

    @Test
    public void testDynamicFeatureAccessInfo()
    {
        DynamicFeatureAccess dfa = BslFactory.eINSTANCE.createDynamicFeatureAccess();
        dfa.setName("Property");

        String info = service.buildEObjectInfo(dfa);

        assertTrue("symbol row", info.contains("| **Symbol** | `Property` |"));
        assertTrue("kind", info.contains("| **Kind** | DynamicFeatureAccess |"));
        assertTrue("emf type row", info.contains("| **EMF type** | DynamicFeatureAccess |"));
    }

    @Test
    public void testInvocationInfoUsesMethodAccessName()
    {
        StaticFeatureAccess called = BslFactory.eINSTANCE.createStaticFeatureAccess();
        called.setName("Message");

        Invocation inv = BslFactory.eINSTANCE.createInvocation();
        inv.setMethodAccess(called);

        String info = service.buildEObjectInfo(inv);

        assertTrue("kind is Invocation", info.contains("| **Kind** | Invocation |"));
        assertTrue("emf type row", info.contains("| **EMF type** | Invocation |"));
        assertTrue("symbol taken from method access", info.contains("| **Symbol** | `Message` |"));
    }

    @Test
    public void testModuleInfo()
    {
        Module module = BslFactory.eINSTANCE.createModule();

        String info = service.buildEObjectInfo(module);

        assertTrue("kind is Module", info.contains("| **Kind** | Module |"));
        assertTrue("emf type row", info.contains("| **EMF type** | Module |"));
    }
}
