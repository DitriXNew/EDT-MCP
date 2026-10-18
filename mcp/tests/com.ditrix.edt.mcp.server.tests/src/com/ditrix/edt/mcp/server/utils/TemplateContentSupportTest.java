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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceRuleFactory;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.MoxelCorePlugin;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.google.inject.Injector;
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

    @Test public void testSpreadsheetWriteMaterializesLegacyProxyPlaceholder() throws Exception
    {
        EObject placeholder = EcoreFactory.eINSTANCE.createEObject();
        ((InternalEObject)placeholder).eSetProxyURI(URI.createURI("unresolved:/legacy/Template.mxlx"));
        assertSpreadsheetPlaceholderWrites(placeholder);
    }

    @Test public void testSpreadsheetWriteMaterializesBareEObjectPlaceholder() throws Exception
    {
        assertSpreadsheetPlaceholderWrites(EcoreFactory.eINSTANCE.createEObject());
    }

    @Test public void testSpreadsheetWriteRefusesForeignContentWithUnsetFeatures() throws Exception
    {
        assertSpreadsheetForeignContentRefused(MdClassFactory.eINSTANCE.createCatalog());
    }

    @Test public void testSpreadsheetWriteRefusesFileBackedTextProxy() throws Exception
    {
        MemoryFile file = new MemoryFile("keep text");
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        initialize("initialize", template, TemplateType.TEXT_DOCUMENT, file);
        assertSpreadsheetForeignContentRefused(template.getTemplate());
    }

    @Test public void testTextWriteRefusesPopulatedSpreadsheetWithoutChangingFilesOrReference() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetTemplateWriter.apply((SpreadsheetDocument)template.getTemplate(),
            json("{'cells':[{'row':0,'col':0,'text':'keep spreadsheet'}]}"));
        assertTextForeignContentRefused(template);
    }

    @Test public void testTextWriteRefusesEmptySpreadsheetContent() throws Exception
    {
        assertTextForeignContentRefused(spreadsheet());
    }

    @Test public void testTextWriteMaterializesLegacyProxyPlaceholder() throws Exception
    {
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        EObject placeholder = EcoreFactory.eINSTANCE.createEObject();
        ((InternalEObject)placeholder).eSetProxyURI(URI.createURI("unresolved:/legacy/Template.txt"));
        template.setTemplate(placeholder);
        MemoryFile file = new MemoryFile(null);
        JsonObject result = invokeTemplateWrite(template, json("{'text':'new text'}"),
            file, new MemoryFile(null), mock(IBmTransaction.class));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        assertEquals("FileAwareTextDocument", template.getTemplate().eClass().getName());
        assertTrue(template.getTemplate().eIsProxy());
    }

    @Test public void testTextWriteAcceptsExistingFileBackedTextProxy() throws Exception
    {
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        MemoryFile file = new MemoryFile("old text");
        initialize("initialize", template, TemplateType.TEXT_DOCUMENT, file);
        JsonObject result = invokeTemplateWrite(template, json("{'text':'new text'}"),
            file, new MemoryFile(null), mock(IBmTransaction.class));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        assertEquals("FileAwareTextDocument", template.getTemplate().eClass().getName());
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

    @Test public void testFreshInteractiveSpreadsheetMatchesWizardBaselineAndCanRetype() throws Exception
    {
        try (AutoCloseable services = wizardServices())
        {
            IDtProject project = mock(IDtProject.class);
            BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
            template.setTemplate(SheetFactory.createInteractiveSpreadsheetDocument(project));
            call("retype", INIT, template, TemplateType.DATA_COMPOSITION_SCHEMA,
                mock(IBmTransaction.class), project, null, null);
            assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, template.getTemplateType());
            assertNull(template.getTemplate());
        }
    }

    @Test public void testInteractiveSpreadsheetPrintSettingRefusesRetypeWithoutModelChange() throws Exception
    {
        try (AutoCloseable services = wizardServices())
        {
            IDtProject project = mock(IDtProject.class);
            BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
            SpreadsheetDocument document = SheetFactory.createInteractiveSpreadsheetDocument(project);
            template.setTemplate(document);
            document.getPrintSettings().setTopMargin(document.getPrintSettings().getTopMargin() + 1000);
            EObject before = EcoreUtil.copy(document);
            IBmTransaction tx = mock(IBmTransaction.class);
            try
            {
                call("retype", INIT, template, TemplateType.DATA_COMPOSITION_SCHEMA, tx, project, null, null);
                fail("Changed wizard document print settings must prevent retyping");
            }
            catch (IllegalArgumentException expected)
            {
                assertTrue(expected.getMessage(), expected.getMessage().contains("content"));
            }
            assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
            assertSame(document, template.getTemplate());
            assertTrue(EcoreUtil.equals(before, document));
            verifyZeroInteractions(tx);
        }
    }

    @Test public void testFreshSpreadsheetBaselineIsEmptyAndCanRetype() throws Exception
    {
        BasicTemplate template = spreadsheet();
        assertFalse(TemplateContentSupport.hasContent(template, null));
        assertEmptyWizardRetype(TemplateType.TEXT_DOCUMENT);
    }

    @Test public void testSpreadsheetPrintSettingCountsAsContentAndRetypeKeepsModel() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetDocument document = (SpreadsheetDocument)template.getTemplate();
        document.getPrintSettings().setTopMargin(2000);
        EObject before = EcoreUtil.copy(document);
        assertTrue("A changed document print setting is authored content",
            TemplateContentSupport.hasContent(template, null));
        assertRetypeRefused(template, TemplateType.TEXT_DOCUMENT, new MemoryFile(null));
        assertTrue("Refused retype must leave all spreadsheet state unchanged", EcoreUtil.equals(before, document));
        assertEquals(2000, document.getPrintSettings().getTopMargin());
    }

    @Test public void testSpreadsheetViewSettingCountsAsContentAndRetypeKeepsModel() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetDocument document = (SpreadsheetDocument)template.getTemplate();
        document.getViewSettings().setShowGrid(false);
        EObject before = EcoreUtil.copy(document);
        assertTrue(TemplateContentSupport.hasContent(template, null));
        assertRetypeRefused(template, TemplateType.DATA_COMPOSITION_SCHEMA, new MemoryFile(null));
        assertTrue(EcoreUtil.equals(before, document));
    }

    @Test public void testSpreadsheetDefaultFormatCountsAsContentAndRetypeKeepsModel() throws Exception
    {
        BasicTemplate template = spreadsheet();
        SpreadsheetDocument document = (SpreadsheetDocument)template.getTemplate();
        document.getFormats().get(document.getDefaultFormatIndex()).setWidth(144);
        EObject before = EcoreUtil.copy(document);
        assertTrue(TemplateContentSupport.hasContent(template, null));
        assertRetypeRefused(template, TemplateType.TEXT_DOCUMENT, new MemoryFile(null));
        assertTrue(EcoreUtil.equals(before, document));
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
        assertEmptyWizardRetype(TemplateType.TEXT_DOCUMENT);
    }

    @Test public void testRetypeEmptySpreadsheetToDcsLeavesTheSchemaToTheDcsTool() throws Exception
    {
        assertEmptyWizardRetype(TemplateType.DATA_COMPOSITION_SCHEMA);
    }

    @Test public void testRetypeWizardSpreadsheetWithoutProjectRequestsRetry() throws Exception
    {
        try (AutoCloseable wizard = wizardServices())
        {
            BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
            template.setTemplate(SheetFactory.createInteractiveSpreadsheetDocument(mock(IDtProject.class)));
            EObject content = template.getTemplate();
            IBmTransaction tx = mock(IBmTransaction.class);
            try
            {
                call("retype", INIT, template, TemplateType.DATA_COMPOSITION_SCHEMA, tx, null, null, null);
                fail("Retype without project services must ask the caller to retry");
            }
            catch (RuntimeException expected)
            {
                assertProjectRetry(expected);
            }
            assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
            assertSame(content, template.getTemplate());
            verifyZeroInteractions(tx);
        }
    }

    @Test public void testRetypeWithoutProjectManagerRequestsRetry() throws Exception
    {
        assertContextProjectRetry(false);
    }

    @Test public void testRetypeWithUnreadyProjectRequestsRetry() throws Exception
    {
        assertContextProjectRetry(true);
    }

    @Test public void testConcurrentSaveBeforeRetypeDeleteRefusesAndKeepsFile() throws Exception
    {
        MemoryFile oldFile = new MemoryFile("");
        MemoryFile newFile = new MemoryFile(null);
        oldFile.pendingSave = "concurrent editor text".getBytes(StandardCharsets.UTF_8);
        BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
        IBmTransaction tx = mock(IBmTransaction.class);
        try (TemplateServices services = new TemplateServices(oldFile, newFile);
            TemplateContentSupport.Context context = new TemplateContentSupport.Context(services.project))
        {
            try
            {
                context.retype(template, TemplateType.DATA_COMPOSITION_SCHEMA, tx);
                context.commit();
                fail("A save between the empty check and delete must prevent retyping");
            }
            catch (IllegalArgumentException expected)
            {
                assertTrue(expected.getMessage(), expected.getMessage().contains("already has content"));
                assertTrue(expected.getMessage(), expected.getMessage().contains("create_metadata"));
            }
            // The in-memory retype is undone by the BM transaction in production: the refusal escapes
            // the write lambda. Here only the file side is observable.
        }
        assertArrayEquals("concurrent editor text".getBytes(StandardCharsets.UTF_8), oldFile.bytes.get());
        assertNull(newFile.bytes.get());
        verify(oldFile.file, never()).delete(anyInt(), any());
        verify(oldFile.file, never()).setContents(any(InputStream.class), anyInt(), any());
    }

    @Test public void testEmptyTextRetypeChecksAndDeletesUnderBothFileRules() throws Exception
    {
        MemoryFile oldFile = new MemoryFile("");
        try (TemplateServices services = new TemplateServices(oldFile, new MemoryFile(null));
            TemplateContentSupport.Context context = new TemplateContentSupport.Context(services.project))
        {
            BasicTemplate template = template(TemplateType.TEXT_DOCUMENT);
            context.retype(template, TemplateType.DATA_COMPOSITION_SCHEMA, mock(IBmTransaction.class));
            context.commit();
            assertSame(TemplateType.DATA_COMPOSITION_SCHEMA, template.getTemplateType());
        }
        assertNull(oldFile.bytes.get());
        assertTrue("Final emptiness check must hold the file's modify rule", oldFile.protectedReads > 0);
        assertTrue("Deletion must hold both modify and delete rules", oldFile.deletedUnderBothRules);
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

    @Test public void testUnrepresentableTextRefusedBeforeJournalAndRepresentableTextWrites() throws Exception
    {
        Charset charset = Charset.forName("windows-1251");
        MemoryFile file = new MemoryFile("original", charset);
        assertEncodingRefused(file, "private text \u6F22");
        String text = "\u041f\u0440\u0438\u0432\u0435\u0442\r\n";
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, text, null));
        assertArrayEquals(text.getBytes(charset), file.bytes.get());
        assertEquals(text, TemplateContentSupport.readText(file.file));
    }

    @Test public void testMalformedTextRefusedBeforeJournal() throws Exception
    {
        assertEncodingRefused(new MemoryFile("original"), "private text \uD800");
    }

    @Test public void testEmptyExistingTextFileKeepsItsDeclaredCharset() throws Exception
    {
        MemoryFile file = new MemoryFile("", Charset.forName("windows-1251"));
        assertEncodingRefused(file, "private text \u6F22");
    }

    @Test public void testNewTextUsesInheritedCharsetAndReadHashMatchesReturnedHash() throws Exception
    {
        Charset charset = Charset.forName("windows-1251");
        MemoryFile file = new MemoryFile(null, charset);
        String text = "\u041f\u0440\u0438\u0432\u0435\u0442\r\n";
        BasicTemplate template = mock(BasicTemplate.class, withSettings().extraInterfaces(IBmObject.class));
        when(template.getTemplateType()).thenReturn(TemplateType.TEXT_DOCUMENT);
        when(template.getName()).thenReturn("Contract730");
        IBmObject bm = (IBmObject)template;
        doReturn(true).when(bm).bmIsTop();
        doReturn("CommonTemplate.Contract730").when(bm).bmGetFqn();
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectById(anyLong())).thenReturn(bm);
        try (TemplateServices services = new TemplateServices(file, new MemoryFile(null)))
        {
            when(services.model.execute(any())).thenAnswer(invocation ->
                ((AbstractBmTask<?>)invocation.getArgument(0)).execute(tx, null));
            Class<?> contextType = Class.forName(
                "com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool$ProjectContext");
            var constructor = contextType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object context = constructor.newInstance();
            Field project = contextType.getField("project");
            project.setAccessible(true);
            project.set(context, services.project);
            var write = ModifyMetadataTool.class.getDeclaredMethod("modifyTextTemplateContent", contextType,
                String.class, BasicTemplate.class, JsonObject.class);
            write.setAccessible(true);
            JsonObject spec = new JsonObject();
            spec.addProperty("text", text);
            JsonObject result = JsonParser.parseString((String)write.invoke(new ModifyMetadataTool(), context,
                "CommonTemplate.Contract730", template, spec)).getAsJsonObject();
            assertTrue(result.toString(), result.get("success").getAsBoolean());
            String returnedHash = result.getAsJsonObject("template").get("contentHash").getAsString();
            assertEquals(text, new String(file.bytes.get(), charset));
            assertEquals(text, TemplateContentSupport.readText(file.file));
            assertEquals(returnedHash, ContentHash.of(TemplateContentSupport.readText(file.file)));
        }
        assertArrayEquals(text.getBytes(charset), file.bytes.get());
        verify(file.file, never()).setCharset(anyString(), any());
    }

    @Test public void testNewTextUnrepresentableInInheritedCharsetRefusesBeforeCreate() throws Exception
    {
        assertEncodingRefused(new MemoryFile(null, Charset.forName("windows-1251")), "private text \u6F22");
    }

    @Test public void testNewDefaultUtf8TextHasNoBom() throws Exception
    {
        MemoryFile file = new MemoryFile(null);
        String text = "\u041f\u0440\u0438\u0432\u0435\u0442\n";
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, text, null));
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    private static void assertEncodingRefused(MemoryFile file, String text) throws Exception
    {
        byte[] original = file.bytes.get() == null ? null : file.bytes.get().clone();
        Class<?> type = Class.forName("com.ditrix.edt.mcp.server.utils.TemplateContentSupport$Context");
        JsonObject spec = new JsonObject();
        spec.addProperty("text", text);
        try (AutoCloseable context = (AutoCloseable)type.getConstructor(org.eclipse.core.resources.IProject.class)
            .newInstance(new Object[] {null}))
        {
            String error = (String)call("writeText",
                new Class<?>[] {BasicTemplate.class, IFile.class, JsonObject.class, type},
                template(TemplateType.TEXT_DOCUMENT), file.file, spec, context);
            assertNotNull("Unencodable text must be refused", error);
            assertFalse(error, JsonParser.parseString(error).getAsJsonObject().get("success").getAsBoolean());
            assertTrue(error, error.contains(file.file.getCharset()));
            assertTrue(error, error.contains("cannot represent"));
            assertFalse(error, error.contains("private text"));
            assertArrayEquals(original, file.bytes.get());
            // A refusal must not enlist the file and later restore over a concurrent edit.
            file.bytes.set("external edit".getBytes(Charset.forName(file.file.getCharset())));
        }
        verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
        verify(file.file, never()).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
        assertArrayEquals("external edit".getBytes(Charset.forName(file.file.getCharset())), file.bytes.get());
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

    @Test public void testConcurrentSaveBeforeTextWriteRefusesAndPreservesBytes() throws Exception
    {
        MemoryFile file = new MemoryFile("original\n");
        file.pendingSave = "concurrent editor text\n".getBytes(StandardCharsets.UTF_8);
        try (TemplateContentSupport.Context context = new TemplateContentSupport.Context(null))
        {
            JsonObject spec = new JsonObject();
            spec.addProperty("text", "lost update\n");
            spec.addProperty("expectedHash", ContentHash.of("original\n"));
            String error = TemplateContentSupport.writeText(template(TemplateType.TEXT_DOCUMENT), file.file,
                spec, context);
            assertNotNull("A save after the early hash check must refuse the write", error);
            assertFalse(error, JsonParser.parseString(error).getAsJsonObject().get("success").getAsBoolean());
            assertTrue(error, error.contains(ContentHash.of("concurrent editor text\n")));
            assertTrue(error, error.contains("get_metadata_details"));
        }
        // Closing an uncommitted context must not restore over the concurrent save after refusal.
        assertArrayEquals("concurrent editor text\n".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
        verify(file.file, never()).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
    }

    @Test public void testFinalTextHashReadAndWriteHoldTheSameModifyRule() throws Exception
    {
        MemoryFile file = new MemoryFile("original");
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, "new", ContentHash.of("original")));
        assertTrue("The final hash read must hold the file's modify rule", file.protectedReads > 0);
        assertTrue("The write must keep that modify rule", file.modifiedUnderRule);
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), file.bytes.get());
    }

    @Test public void testExpectedHashOfEmptyTextOnMissingFileWrites() throws Exception
    {
        MemoryFile file = new MemoryFile(null);
        assertEquals("", TemplateContentSupport.readText(file.file));
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, "new", ContentHash.of("")));
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        assertTrue("Creation and write must hold the file's modify rule", file.modifiedUnderRule);
    }

    @Test public void testOtherExpectedHashOnMissingFileRefusesWithEmptyCurrentHash() throws Exception
    {
        MemoryFile file = new MemoryFile(null);
        String error = write(template(TemplateType.TEXT_DOCUMENT), file, "new", ContentHash.of("old"));
        assertEmptyCurrentHashRefusal(error);
        assertNull(file.bytes.get());
        verify(file.file, never()).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
        verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
        verifyZeroInteractions(file.workspace);
    }

    @Test public void testConcurrentCreationRefusesEmptyExpectedHashAndPreservesBytes() throws Exception
    {
        MemoryFile file = new MemoryFile(null);
        file.pendingSave = "concurrent creation".getBytes(StandardCharsets.UTF_8);
        try (TemplateContentSupport.Context context = new TemplateContentSupport.Context(null))
        {
            JsonObject spec = json("{'text':'lost update'}");
            spec.addProperty("expectedHash", ContentHash.of(""));
            String error = TemplateContentSupport.writeText(template(TemplateType.TEXT_DOCUMENT),
                file.file, spec, context);
            assertNotNull("The final check must detect a file created after the early check", error);
            assertTrue(error, error.contains(ContentHash.of("concurrent creation")));
        }
        assertArrayEquals("concurrent creation".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        verify(file.file, never()).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
        verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
    }

    @Test public void testFileDeletedBeforeFinalHashCheckAcceptsEmptyExpectedHash() throws Exception
    {
        MemoryFile file = new MemoryFile("");
        file.pendingDelete = true;
        assertNull(write(template(TemplateType.TEXT_DOCUMENT), file, "new", ContentHash.of("")));
        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), file.bytes.get());
        assertTrue("Final check and recreated content must hold the modify rule", file.modifiedUnderRule);
    }

    @Test public void testFileDeletedBeforeFinalHashCheckRefusesWithEmptyCurrentHash() throws Exception
    {
        MemoryFile file = new MemoryFile("old");
        file.pendingDelete = true;
        String error = write(template(TemplateType.TEXT_DOCUMENT), file, "lost", ContentHash.of("old"));
        assertEmptyCurrentHashRefusal(error);
        assertNull(file.bytes.get());
        verify(file.file, never()).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
        verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
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

    private static void assertSpreadsheetPlaceholderWrites(EObject placeholder) throws Exception
    {
        BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
        template.setTemplate(placeholder);
        IBmTransaction tx = mock(IBmTransaction.class);
        final SpreadsheetDocument document;
        try
        {
            document = resolveSpreadsheet(template, tx);
        }
        catch (RuntimeException refusal)
        {
            throw new AssertionError("A legacy placeholder must be materialized: " + refusal.getMessage(), refusal);
        }
        SpreadsheetTemplateWriter.Result result = SpreadsheetTemplateWriter.apply(document,
            json("{'cells':[{'row':0,'col':0,'text':'legacy cell'}]}"));
        assertFalse(result.error, result.hasError());
        assertEquals(1, result.cells);
        assertSame(document, template.getTemplate());
        assertTrue(document.isTemplateMode());
        assertEquals("legacy cell", document.getRows().get(Integer.valueOf(0))
            .getCells().get(Integer.valueOf(0)).getText().getContent().get("#"));
        verify(tx).attachTopObject((IBmObject)document, "CommonTemplate.Contract730.Content");
    }

    private static void assertSpreadsheetForeignContentRefused(EObject content) throws Exception
    {
        BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
        template.setTemplate(content);
        IBmTransaction tx = mock(IBmTransaction.class);
        try
        {
            resolveSpreadsheet(template, tx);
            fail("Real foreign content must be refused before replacement");
        }
        catch (RuntimeException expected)
        {
            String error = expected.getMessage();
            assertFalse(error, JsonParser.parseString(error).getAsJsonObject().get("success").getAsBoolean());
            assertTrue(error, error.contains("has incompatible content"));
            assertTrue(error, error.contains("SpreadsheetDocument"));
            assertTrue(error, error.contains("create_metadata"));
        }
        assertSame(content, template.getTemplate());
        verify(tx, never()).attachTopObject(any(), anyString());
    }

    /** Exercise the tool's resolver and the real writer without simulating BM export internals. */
    private static SpreadsheetDocument resolveSpreadsheet(BasicTemplate template, IBmTransaction tx) throws Exception
    {
        ITopObjectFqnGenerator names = mock(ITopObjectFqnGenerator.class);
        when(names.generateExternalPropertyFqn(any(), any())).thenReturn("CommonTemplate.Contract730.Content");
        var resolve = ModifyMetadataTool.class.getDeclaredMethod("resolveSpreadsheetContent",
            BasicTemplate.class, IBmTransaction.class, IDtProject.class, ITopObjectFqnGenerator.class, String.class);
        resolve.setAccessible(true);
        try
        {
            return (SpreadsheetDocument)resolve.invoke(null, template, tx, null, names, "CommonTemplate.Contract730");
        }
        catch (InvocationTargetException error)
        {
            if (error.getCause() instanceof Exception) { throw (Exception)error.getCause(); }
            throw error;
        }
    }

    private static void assertTextForeignContentRefused(BasicTemplate template) throws Exception
    {
        EObject content = template.getTemplate();
        EObject before = EcoreUtil.copy(content);
        template.setTemplateType(TemplateType.TEXT_DOCUMENT);
        MemoryFile text = new MemoryFile("keep text sidecar");
        MemoryFile spreadsheet = new MemoryFile("keep spreadsheet sidecar");
        IBmTransaction tx = mock(IBmTransaction.class);
        JsonObject result = invokeTemplateWrite(template, json("{'text':'lost update'}"),
            text, spreadsheet, tx);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertTrue(result.toString(), result.toString().contains("has incompatible content"));
        assertTrue(result.toString(),
            result.toString().contains("Create a separate TextDocument template with create_metadata"));
        assertSame(content, template.getTemplate());
        assertTrue(EcoreUtil.equals(before, content));
        assertSame(TemplateType.TEXT_DOCUMENT, template.getTemplateType());
        assertArrayEquals("keep text sidecar".getBytes(StandardCharsets.UTF_8), text.bytes.get());
        assertArrayEquals("keep spreadsheet sidecar".getBytes(StandardCharsets.UTF_8), spreadsheet.bytes.get());
        for (MemoryFile file : new MemoryFile[] {text, spreadsheet})
        {
            verify(file.file, never()).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
            verify(file.file, never()).setContents(any(InputStream.class), anyInt(), any());
            verify(file.file, never()).delete(anyInt(), any());
        }
        verify(tx, never()).attachTopObject(any(), anyString());
        verify(tx, never()).detachTopObject(any());
    }

    /** Dispatch with a fresh transaction object, so the guard must inspect current content. */
    private static JsonObject invokeTemplateWrite(BasicTemplate current, JsonObject spec,
        MemoryFile text, MemoryFile other, IBmTransaction tx) throws Exception
    {
        BasicTemplate outer = mock(BasicTemplate.class, withSettings().extraInterfaces(IBmObject.class));
        when(outer.getTemplateType()).thenReturn(current.getTemplateType());
        IBmObject bm = (IBmObject)outer;
        doReturn(true).when(bm).bmIsTop();
        doReturn(730L).when(bm).bmGetId();
        doReturn("CommonTemplate.Contract730").when(bm).bmGetFqn();
        when(tx.getObjectById(730L)).thenReturn((IBmObject)current);
        try (TemplateServices services = new TemplateServices(text, other))
        {
            ITopObjectFqnGenerator names = mock(ITopObjectFqnGenerator.class);
            when(names.generateExternalPropertyFqn(any(), any())).thenReturn("CommonTemplate.Contract730.Content");
            when(services.activator.getTopObjectFqnGenerator()).thenReturn(names);
            when(services.model.execute(any())).thenAnswer(invocation ->
                ((AbstractBmTask<?>)invocation.getArgument(0)).execute(tx, null));
            Class<?> contextType = Class.forName(
                "com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool$ProjectContext");
            var constructor = contextType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object context = constructor.newInstance();
            Field project = contextType.getField("project");
            project.setAccessible(true);
            project.set(context, services.project);
            var write = ModifyMetadataTool.class.getDeclaredMethod("modifyTemplateContent", contextType,
                String.class, BasicTemplate.class, java.util.List.class, java.util.List.class,
                boolean.class, JsonObject.class);
            write.setAccessible(true);
            return JsonParser.parseString((String)write.invoke(new ModifyMetadataTool(), context,
                "CommonTemplate.Contract730", outer, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), false, spec)).getAsJsonObject();
        }
    }

    private static void assertEmptyCurrentHashRefusal(String error)
    {
        assertNotNull("A non-empty prior text hash must not match a missing file", error);
        assertFalse(error, JsonParser.parseString(error).getAsJsonObject().get("success").getAsBoolean());
        assertTrue(error, error.contains("expectedHash does not match"));
        assertTrue(error, error.contains("Current contentHash: " + ContentHash.of("")));
        assertTrue(error, error.contains("get_metadata_details"));
    }

    /** Supply factory services locally; the real factory still creates every model feature. */
    private static AutoCloseable wizardServices() throws Exception
    {
        Injector injector = mock(Injector.class);
        when(injector.getInstance(any(Class.class))).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(0);
            if (type == IV8ProjectManager.class) { return mock(IV8ProjectManager.class); }
            if (type.getName().equals("com.e1c.g5.dt.core.api.platform.IDtProjectRuntimeVersionManager"))
            {
                return mock(type, call -> call.getMethod().getName().equals("getRuntimeVersion")
                    ? Version.LATEST : RETURNS_DEFAULTS.answer(call));
            }
            throw new AssertionError("Unexpected wizard service: " + type.getName());
        });
        MoxelCorePlugin plugin = mock(MoxelCorePlugin.class);
        when(plugin.getInjector()).thenReturn(injector);
        Field instance = MoxelCorePlugin.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, plugin);
        return () -> instance.set(null, previous);
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
        if (method.equals("retype") && template.getTemplate() instanceof SpreadsheetDocument)
        {
            try (AutoCloseable services = wizardServices())
            {
                call(method, INIT, template, type, mock(IBmTransaction.class), mock(IDtProject.class), names, file.file);
            }
        }
        else
        {
            call(method, INIT, template, type, mock(IBmTransaction.class), null, names, file.file);
        }
    }

    private static void assertEmptyWizardRetype(TemplateType type) throws Exception
    {
        try (AutoCloseable services = wizardServices())
        {
            IDtProject project = mock(IDtProject.class);
            BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
            template.setTemplate(SheetFactory.createInteractiveSpreadsheetDocument(project));
            MemoryFile file = new MemoryFile(null);
            call("retype", INIT, template, type, mock(IBmTransaction.class), project, null, file.file);
            assertSame(type, template.getTemplateType());
            if (type == TemplateType.TEXT_DOCUMENT) { assertArrayEquals(new byte[0], file.bytes.get()); }
            else { assertNull(template.getTemplate()); }
        }
    }

    private static void assertContextProjectRetry(boolean hasManager) throws Exception
    {
        MemoryFile oldFile = new MemoryFile("unchanged spreadsheet sidecar");
        MemoryFile newFile = new MemoryFile(null);
        try (AutoCloseable wizard = wizardServices();
            TemplateServices services = new TemplateServices(newFile, oldFile);
            TemplateContentSupport.Context context = new TemplateContentSupport.Context(services.project))
        {
            if (!hasManager) { when(services.activator.getDtProjectManager()).thenReturn(null); }
            BasicTemplate template = template(TemplateType.SPREADSHEET_DOCUMENT);
            template.setTemplate(SheetFactory.createInteractiveSpreadsheetDocument(mock(IDtProject.class)));
            EObject content = template.getTemplate();
            IBmTransaction tx = mock(IBmTransaction.class);
            try
            {
                context.retype(template, TemplateType.TEXT_DOCUMENT, tx);
                fail("Missing project services must ask the caller to retry");
            }
            catch (RuntimeException expected)
            {
                assertProjectRetry(expected);
            }
            assertSame(TemplateType.SPREADSHEET_DOCUMENT, template.getTemplateType());
            assertSame(content, template.getTemplate());
            verifyZeroInteractions(tx);
        }
        assertArrayEquals("unchanged spreadsheet sidecar".getBytes(StandardCharsets.UTF_8), oldFile.bytes.get());
        assertNull(newFile.bytes.get());
    }

    private static void assertProjectRetry(RuntimeException error)
    {
        assertTrue("Must report unavailable services instead of authored content: " + error,
            error instanceof IllegalStateException);
        String message = error.getMessage().toLowerCase(java.util.Locale.ROOT);
        assertTrue(message, message.contains("project services") && message.contains("not available yet"));
        assertTrue(message, message.contains("retry") && message.contains("project is ready"));
        assertFalse(message, message.contains("already has content"));
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

    /** Resolve the real Context and tool paths without a running EDT or filesystem preferences. */
    private static final class TemplateServices implements AutoCloseable
    {
        final IProject project = mock(IProject.class);
        final Activator activator = mock(Activator.class);
        final IBmModel model = mock(IBmModel.class);
        private final Field metadataPlugin = MdPlugin.class.getDeclaredField("plugin");
        private final Field serverPlugin = Activator.class.getDeclaredField("plugin");
        private final Object previousMetadata;
        private final Object previousServer;

        TemplateServices(MemoryFile text, MemoryFile other) throws Exception
        {
            MdPlugin plugin = mock(MdPlugin.class);
            Injector injector = mock(Injector.class);
            IProjectFileSystemSupportProvider provider = mock(IProjectFileSystemSupportProvider.class);
            IProjectFileSystemSupport filesystem = mock(IProjectFileSystemSupport.class);
            when(plugin.getInjector()).thenReturn(injector);
            when(injector.getInstance(IProjectFileSystemSupportProvider.class)).thenReturn(provider);
            when(provider.getProjectFileSystemSupport(project)).thenReturn(filesystem);
            when(filesystem.getFile(any(), any())).thenAnswer(invocation ->
                ((BasicTemplate)invocation.getArgument(0)).getTemplateType() == TemplateType.TEXT_DOCUMENT
                    ? text.file : other.file);
            when(activator.getDtProjectManager()).thenReturn(mock(IDtProjectManager.class));
            IBmModelManager models = mock(IBmModelManager.class);
            when(activator.getBmModelManager()).thenReturn(models);
            when(models.getModel(project)).thenReturn(model);
            metadataPlugin.setAccessible(true);
            serverPlugin.setAccessible(true);
            previousMetadata = metadataPlugin.get(null);
            previousServer = serverPlugin.get(null);
            metadataPlugin.set(null, plugin);
            serverPlugin.set(null, activator);
        }

        @Override public void close() throws Exception
        {
            serverPlugin.set(null, previousServer);
            metadataPlugin.set(null, previousMetadata);
        }
    }

    private static final class MemoryFile
    {
        final IFile file = mock(IFile.class);
        final AtomicReference<byte[]> bytes = new AtomicReference<>();
        final IWorkspace workspace = mock(IWorkspace.class);
        final ISchedulingRule modifyRule = mock(ISchedulingRule.class);
        final ISchedulingRule deleteRule = mock(ISchedulingRule.class);
        ISchedulingRule activeRule;
        byte[] pendingSave;
        boolean pendingDelete;
        int protectedReads;
        boolean modifiedUnderRule;
        boolean deletedUnderBothRules;

        MemoryFile(String text) throws Exception
        {
            this(text, StandardCharsets.UTF_8);
        }

        MemoryFile(String text, Charset charset) throws Exception
        {
            bytes.set(text == null ? null : text.getBytes(charset));
            when(file.exists()).thenAnswer(i -> bytes.get() != null);
            when(file.getContents()).thenAnswer(i -> {
                if (holds(modifyRule)) { protectedReads++; }
                return new ByteArrayInputStream(bytes.get());
            });
            when(file.getCharset()).thenReturn(charset.name());
            when(file.getFullPath()).thenReturn(new Path(
                "/Test/src/DataProcessors/Owner/Templates/Contract/Template.txt"));
            when(file.getWorkspace()).thenReturn(workspace);
            IResourceRuleFactory rules = mock(IResourceRuleFactory.class);
            when(workspace.getRuleFactory()).thenReturn(rules);
            when(rules.modifyRule(file)).thenReturn(modifyRule);
            when(rules.deleteRule(file)).thenReturn(deleteRule);
            when(modifyRule.contains(any())).thenAnswer(i -> i.getArgument(0) == modifyRule);
            when(deleteRule.contains(any())).thenAnswer(i -> i.getArgument(0) == deleteRule);
            doAnswer(i -> {
                saveBeforeOperation();
                activeRule = i.getArgument(1);
                try { ((ICoreRunnable)i.getArgument(0)).run(i.getArgument(3)); }
                finally { activeRule = null; }
                return null;
            }).when(workspace).run(any(ICoreRunnable.class), any(ISchedulingRule.class), anyInt(), any());
            doAnswer(i -> {
                if (activeRule == null) { saveBeforeOperation(); }
                modifiedUnderRule = holds(modifyRule);
                bytes.set(((InputStream)i.getArgument(0)).readAllBytes());
                return null;
            }).when(file).setContents(any(InputStream.class), anyInt(), any());
            doAnswer(i -> {
                bytes.set(((InputStream)i.getArgument(0)).readAllBytes());
                return null;
            }).when(file).create(any(InputStream.class), anyBoolean(), any(IProgressMonitor.class));
            doAnswer(i -> {
                if (activeRule == null) { saveBeforeOperation(); }
                deletedUnderBothRules = holds(modifyRule) && holds(deleteRule);
                bytes.set(null);
                return null;
            }).when(file).delete(anyInt(), any());
        }

        private boolean holds(ISchedulingRule rule)
        {
            return activeRule != null && activeRule.contains(rule);
        }

        /** A queued editor save wins just before the next workspace mutation acquires its rule. */
        private void saveBeforeOperation()
        {
            if (pendingDelete)
            {
                bytes.set(null);
                pendingDelete = false;
            }
            if (pendingSave != null)
            {
                bytes.set(pendingSave);
                pendingSave = null;
            }
        }
    }
}
