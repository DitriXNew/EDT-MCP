/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises actual content mutations on EDT objects and a byte-backed Eclipse file.
 * Reflection keeps these tests runnable on the untouched base for red evidence.
 */
public class TemplateContentSupportTest
{
    private static final Class<?>[] INIT = {BasicTemplate.class, TemplateType.class,
        IBmTransaction.class, IDtProject.class, ITopObjectFqnGenerator.class, IFile.class};

    @Test public void testTextInitializationCreatesEmptyFileAndPlatformProxy() throws Exception
    {
        MemoryFile file = new MemoryFile(null);
        BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
        initialize("initialize", template, TemplateType.TEXT_DOCUMENT, file);
        assertSame(TemplateType.TEXT_DOCUMENT, template.getTemplateType());
        assertArrayEquals(new byte[0], file.bytes.get());
        EObject proxy = (EObject)template.eGet(
            com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE, false);
        assertEquals("FileAwareTextDocument", proxy.eClass().getName());
        assertTrue(proxy.eIsProxy());
        assertEquals("platform:/resource/Test/src/DataProcessors/Owner/Templates/Contract/Template.txt#/",
            ((InternalEObject)proxy).eProxyURI().toString());
    }

    @Test public void testSpreadsheetInitializationUsesDesignerDefaults() throws Exception
    {
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        initialize("initialize", template, TemplateType.SPREADSHEET_DOCUMENT, new MemoryFile(null));
        assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
        SpreadsheetDocument doc = (SpreadsheetDocument)template.getTemplate();
        assertTrue(doc.isTemplateMode());
        assertFalse(doc.getFormats().isEmpty());
        assertEquals(0, doc.getDefaultFormatIndex());
    }

    @Test public void testRetypeTextWithContentRefusedWithoutChangingBytes() throws Exception
    {
        MemoryFile file = new MemoryFile(" \n");
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        assertRetypeRefused(template, TemplateType.SPREADSHEET_DOCUMENT, file);
        assertArrayEquals(" \n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testRetypeSpreadsheetWithCellRefused() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetTemplateWriter.apply((SpreadsheetDocument)template.getTemplate(),
            json("{'cells':[{'row':0,'col':0,'text':'keep'}]}"));
        assertRetypeRefused(template, TemplateType.TEXT_DOCUMENT, new MemoryFile(null));
        // The old plain-assignment bug can leave a text type pointing at a populated spreadsheet.
        // An empty/missing Template.txt must not hide that actual content.
        template.setTemplateType(TemplateType.TEXT_DOCUMENT);
        assertRetypeRefused(template, TemplateType.SPREADSHEET_DOCUMENT, new MemoryFile(""));
    }

    @Test public void testRetypeSpreadsheetWithAreaRefused() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetTemplateWriter.apply((SpreadsheetDocument)template.getTemplate(),
            json("{'areas':[{'name':'Keep','fromRow':0,'fromCol':0,'toRow':1,'toCol':1}]}"));
        assertRetypeRefused(template, TemplateType.TEXT_DOCUMENT, new MemoryFile(null));
    }

    @Test public void testRetypeOtherContentRefused() throws Exception
    {
        BasicTemplate template = template(TemplateType.DATA_COMPOSITION_SCHEMA);
        var content = MdClassFactory.eINSTANCE.createCatalog();
        content.setName("KeepContent");
        template.setTemplate(content);
        assertRetypeRefused(template, TemplateType.TEXT_DOCUMENT, new MemoryFile(null));
    }

    @Test public void testRetypeEmptyTextToSpreadsheetAllowed() throws Exception
    {
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        initialize("retype", template, TemplateType.SPREADSHEET_DOCUMENT, new MemoryFile(""));
        assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
        assertTrue(template.getTemplate() instanceof SpreadsheetDocument);
    }

    @Test public void testRetypeOfAnEmptyTextTemplateDoesNotDetachItsUnattachedProxy() throws Exception
    {
        // The live shape: a text template created by initialize() holds a FileAwareTextDocument
        // proxy with no BM namespace, and the platform's detachTopObject throws on such an object.
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        MemoryFile file = new MemoryFile(null);
        initialize("initialize", template, TemplateType.TEXT_DOCUMENT, file);
        assertTrue(template.getTemplate().eIsProxy());
        IBmTransaction tx = mock(IBmTransaction.class);
        doThrow(new NullPointerException("ns is null")).when(tx)
            .detachTopObject(argThat(object -> object.bmGetNamespace() == null));
        ITopObjectFqnGenerator names = mock(ITopObjectFqnGenerator.class);
        when(names.generateExternalPropertyFqn(any(), any())).thenReturn("Template.Contract.Content");
        call("retype", INIT, template, TemplateType.SPREADSHEET_DOCUMENT, tx, null, names, file.file);
        assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
        assertTrue(template.getTemplate() instanceof SpreadsheetDocument);
    }

    @Test public void testRetypeEmptySpreadsheetToTextAllowed() throws Exception
    {
        BasicTemplate template = spreadsheet();
        MemoryFile file = new MemoryFile(null);
        initialize("retype", template, TemplateType.TEXT_DOCUMENT, file);
        assertSame(TemplateType.TEXT_DOCUMENT, template.getTemplateType());
        assertArrayEquals(new byte[0], file.bytes.get());
    }

    @Test public void testRetypeEmptySpreadsheetToDcsLeavesTheSchemaToTheDcsTool() throws Exception
    {
        BasicTemplate template = spreadsheet();
        initialize("retype", template, TemplateType.DATA_COMPOSITION_SCHEMA, new MemoryFile(null));
        assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, template.getTemplateType());
        assertNull(template.getTemplate());
    }

    @Test public void testRetypeSpreadsheetWithCellToDcsRefused() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetTemplateWriter.apply((SpreadsheetDocument)template.getTemplate(),
            json("{'cells':[{'row':0,'col':0,'text':'keep'}]}"));
        assertRetypeRefused(template, TemplateType.DATA_COMPOSITION_SCHEMA, new MemoryFile(null));
    }

    @Test public void testRetypeToAnotherContentTypeStillRefused() throws Exception
    {
        BasicTemplate template = spreadsheet();
        EObject before = template.getTemplate();
        try
        {
            initialize("retype", template, TemplateType.BINARY_DATA, new MemoryFile(null));
            fail("BinaryData has no supported content model");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unsupported templateType"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("DataCompositionSchema"));
        }
        assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
        assertSame(before, template.getTemplate());
        assertNull(TemplateContentSupport.unsupportedRetypeError(TemplateType.DATA_COMPOSITION_SCHEMA, "x"));
        assertNotNull(TemplateContentSupport.unsupportedTypeError(TemplateType.DATA_COMPOSITION_SCHEMA, "x"));
    }

    @Test public void testSameTypeWithContentIsNoOp() throws Exception
    {
        BasicTemplate template = spreadsheet();
        EObject content = template.getTemplate();
        SpreadsheetTemplateWriter.apply((SpreadsheetDocument)content,
            json("{'cells':[{'row':0,'col':0,'text':'keep'}]}"));
        initialize("retype", template, TemplateType.SPREADSHEET_DOCUMENT, new MemoryFile(null));
        assertSame(content, template.getTemplate());
    }

    @Test public void testWriteTextPreservesUtf8NewlinesAndTrailingNewline() throws Exception
    {
        MemoryFile file = new MemoryFile("");
        String text = "{\n  \"value\": \"\u041f\u0440\u0438\u0432\u0435\u0442\"\n}\n";
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, text, null));
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testExpectedHashMatchesModuleHelperIncludingQuotesCaseAndCrLf() throws Exception
    {
        MemoryFile file = new MemoryFile("old\r\n");
        String hash = " \"" + ContentHash.of("old\n").toUpperCase(java.util.Locale.ROOT) + "\" ";
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, "new\n", hash));
        assertArrayEquals("new\n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testStaleExpectedHashRefusesAndGivesCurrentHash() throws Exception
    {
        MemoryFile file = new MemoryFile("current\n");
        String error = write(template(TemplateType.TEXT_DOCUMENT), file, "lost\n", ContentHash.of("old\n"));
        assertNotNull(error);
        assertTrue(error, error.contains(ContentHash.of("current\n")));
        assertTrue(error, error.contains("get_metadata_details"));
        assertArrayEquals("current\n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testExpectedHashOnMissingFileRefused() throws Exception
    {
        MemoryFile file = new MemoryFile(null);
        String error = write(template(TemplateType.TEXT_DOCUMENT), file, "new", ContentHash.of(""));
        assertNotNull(error);
        assertTrue(error, error.contains("omit expectedHash"));
        assertNull(file.bytes.get());
    }

    @Test public void testEmptyExpectedHashDisablesGuard() throws Exception
    {
        MemoryFile file = new MemoryFile("old");
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, "", ""));
        assertArrayEquals(new byte[0], file.bytes.get());
    }

    @Test public void testTextOnSpreadsheetRefusedWithoutWriting() throws Exception
    {
        MemoryFile file = new MemoryFile("original");
        String error = write(spreadsheet(), file, "wrong", null);
        assertNotNull(error);
        assertTrue(error, error.contains("TextDocument"));
        assertTrue(error, error.contains("cells"));
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testCellsOnTextRefused() throws Exception
    {
        String error = (String)call("payloadError",
            new Class<?>[] {BasicTemplate.class, JsonObject.class, String.class},
            template(TemplateType.TEXT_DOCUMENT), json("{'cells':[]}"), "DataProcessor.Owner.Template.Contract");
        assertNotNull(error);
        assertTrue(error, error.contains("cells"));
        assertTrue(error, error.contains("text"));
    }

    @Test public void testTextAndCellsTogetherRefused() throws Exception
    {
        String error = (String)call("payloadError",
            new Class<?>[] {BasicTemplate.class, JsonObject.class, String.class},
            template(TemplateType.TEXT_DOCUMENT), json("{'text':'x','cells':[]}"), "Template.Contract");
        assertNotNull(error);
        assertTrue(error, error.contains("cells"));
    }

    @Test public void testNonStringTextRefusedWithoutWriting() throws Exception
    {
        MemoryFile file = new MemoryFile("original");
        String error = (String)call("writeText",
            new Class<?>[] {BasicTemplate.class, IFile.class, JsonObject.class},
            template(TemplateType.TEXT_DOCUMENT), file.file, json("{'text':5}"));
        assertNotNull(error);
        assertTrue(error, error.contains("string"));
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testDetailsTextAndFullContentHash() throws Exception
    {
        String text = "line | value\n```\n\u041f\u0440\u0438\u0432\u0435\u0442\n";
        String rendered = render(text);
        assertTrue(rendered, rendered.contains("## Text template"));
        assertTrue(rendered, rendered.contains(text));
        assertTrue(rendered, rendered.contains("contentHash: " + ContentHash.of(text)));
        assertTrue(rendered, rendered.contains("````text\n"));
    }

    @Test public void testDetailsLargeTextBoundedButHashCoversFullText() throws Exception
    {
        String text = "x".repeat(OutputSizeGuard.MAX_CONTENT_CHARS * 2) + "tail";
        String rendered = render(text);
        assertTrue(rendered.length() <= OutputSizeGuard.MAX_CONTENT_CHARS);
        assertTrue(rendered, rendered.contains("[OUTPUT TRUNCATED]"));
        assertTrue(rendered, rendered.contains(ContentHash.of(text)));
        assertFalse(rendered, rendered.contains(ContentHash.of(text.substring(0, 1000))));
    }

    @Test public void testDetailsFenceCannotBreakTheOutputBudget() throws Exception
    {
        String rendered = render("`".repeat(OutputSizeGuard.MAX_CONTENT_CHARS));
        assertTrue(rendered.length() <= OutputSizeGuard.MAX_CONTENT_CHARS);
        assertTrue(rendered.contains("[OUTPUT TRUNCATED]"));
    }

    @Test public void testExistingUtf8BomPreservedAndExcludedFromHash() throws Exception
    {
        MemoryFile file = new MemoryFile("\uFEFFold\r\n");
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, "new\n", ContentHash.of("old\n")));
        assertArrayEquals("\uFEFFnew\n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testFailedBmWriteRestoresOriginalTextBytes() throws Exception
    {
        MemoryFile file = new MemoryFile("original\n");
        Class<?> type = Class.forName("com.ditrix.edt.mcp.server.utils.TemplateContentSupport$Context");
        try (AutoCloseable context = (AutoCloseable)type.getConstructor(org.eclipse.core.resources.IProject.class)
            .newInstance(new Object[] {null}))
        {
            assertNull(call("writeText", new Class<?>[] {BasicTemplate.class, IFile.class, JsonObject.class, type},
                template(TemplateType.TEXT_DOCUMENT), file.file, json("{'text':'uncommitted\\n'}"), context));
            assertArrayEquals("uncommitted\n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        }
        assertArrayEquals("original\n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testStaleHashInsideFileContextDoesNotWriteOrRestore() throws Exception
    {
        MemoryFile file = new MemoryFile("current");
        Class<?> type = Class.forName("com.ditrix.edt.mcp.server.utils.TemplateContentSupport$Context");
        try (AutoCloseable context = (AutoCloseable)type.getConstructor(org.eclipse.core.resources.IProject.class)
            .newInstance(new Object[] {null}))
        {
            String error = (String)call("writeText",
                new Class<?>[] {BasicTemplate.class, IFile.class, JsonObject.class, type},
                template(TemplateType.TEXT_DOCUMENT), file.file,
                json("{'text':'lost','expectedHash':'stale'}"), context);
            assertNotNull(error);
        }
        verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
        assertArrayEquals("current".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    private static String render(String text) throws Exception
    {
        return (String)call("renderText", new Class<?>[] {String.class, String.class},
            "DataProcessor.Owner.Template.Contract", text);
    }

    private static String write(BasicTemplate template, MemoryFile file, String text, String hash)
        throws Exception
    {
        JsonObject spec = new JsonObject();
        spec.addProperty("text", text);
        if (hash != null) { spec.addProperty("expectedHash", hash); }
        return (String)call("writeText", new Class<?>[] {BasicTemplate.class, IFile.class, JsonObject.class},
            template, file.file, spec);
    }

    private static void initialize(String method, BasicTemplate template, TemplateType type, MemoryFile file)
        throws Exception
    {
        ITopObjectFqnGenerator names = mock(ITopObjectFqnGenerator.class);
        when(names.generateExternalPropertyFqn(any(), any())).thenReturn("Template.Contract.Content");
        call(method, INIT, template, type, mock(IBmTransaction.class), null, names, file.file);
    }

    private static void assertRetypeRefused(BasicTemplate template, TemplateType type, MemoryFile file)
        throws Exception
    {
        TemplateType beforeType = template.getTemplateType();
        EObject before = template.getTemplate();
        try
        {
            initialize("retype", template, type, file);
            fail("Retyping non-empty content must refuse");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("content"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("create_metadata"));
        }
        assertSame(beforeType, template.getTemplateType());
        assertSame(before, template.getTemplate());
    }

    private static BasicTemplate template(TemplateType type)
    {
        BasicTemplate template = MdClassFactory.eINSTANCE.createTemplate();
        template.setName("Contract");
        template.setTemplateType(type);
        return template;
    }

    private static BasicTemplate spreadsheet()
    {
        BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
        SpreadsheetDocument doc = SheetFactory.createSpreadsheetDocument();
        doc.setTemplateMode(true);
        template.setTemplate(doc);
        return template;
    }

    private static JsonObject json(String value)
    {
        return JsonParser.parseString(value.replace('\'', '"')).getAsJsonObject();
    }

    private static Object call(String name, Class<?>[] types, Object... arguments) throws Exception
    {
        try
        {
            return Class.forName("com.ditrix.edt.mcp.server.utils.TemplateContentSupport")
                .getMethod(name, types).invoke(null, arguments);
        }
        catch (InvocationTargetException error)
        {
            if (error.getCause() instanceof Exception) { throw (Exception)error.getCause(); }
            throw error;
        }
    }

    private static final class MemoryFile
    {
        final IFile file = mock(IFile.class);
        final AtomicReference<byte[]> bytes = new AtomicReference<>();

        MemoryFile(String text) throws Exception
        {
            bytes.set(text == null ? null : text.getBytes(StandardCharsets.UTF_8));
            when(file.exists()).thenAnswer(i -> bytes.get() != null);
            when(file.getContents()).thenAnswer(i -> new ByteArrayInputStream(bytes.get()));
            when(file.getCharset()).thenReturn("UTF-8");
            when(file.getFullPath()).thenReturn(new Path(
                "/Test/src/DataProcessors/Owner/Templates/Contract/Template.txt"));
            doAnswer(i -> {
                bytes.set(((InputStream)i.getArgument(0)).readAllBytes());
                return null;
            }).when(file).setContents(any(InputStream.class), anyInt(), any());
            doAnswer(i -> {
                bytes.set(((InputStream)i.getArgument(0)).readAllBytes());
                return null;
            }).when(file).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
        }
    }
}
