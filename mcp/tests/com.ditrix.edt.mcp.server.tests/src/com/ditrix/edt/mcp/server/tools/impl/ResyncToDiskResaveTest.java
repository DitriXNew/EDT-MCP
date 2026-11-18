/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The scoped re-save of {@code resync_to_disk} ({@code objects}): the pre-model refusals, the address a
 * requested FQN resolves through - in English and in Russian - and how the files are judged after the
 * export. The export itself needs a live workspace and is covered by the e2e suite.
 */
public class ResyncToDiskResaveTest
{
    /** {@code Справочник} - the Russian Catalog type token. */
    private static final String RU_CATALOG = "Справочник"; //$NON-NLS-1$
    /** {@code Форма} - the Russian Form kind token. */
    private static final String RU_FORM = "Форма"; //$NON-NLS-1$
    /** {@code ОбщаяФорма} - the Russian CommonForm type token. */
    private static final String RU_COMMON_FORM = "ОбщаяФорма"; //$NON-NLS-1$
    /** {@code Товары} - a Russian programmatic Name. */
    private static final String RU_NAME = "Товары"; //$NON-NLS-1$

    private static final List<String> ONE = List.of("Catalog.Goods.Form.ItemForm"); //$NON-NLS-1$

    // --- the guard ------------------------------------------------------------------------------

    @Test
    public void testTheSchemaDeclaresObjectsWithItsShapeAndItsConfirmation()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        assertTrue(schema, schema.contains("\"objects\"")); //$NON-NLS-1$
        assertTrue("the value shape is shown by an example: " + schema, //$NON-NLS-1$
            schema.contains("Catalog.Goods.Form.ItemForm")); //$NON-NLS-1$
        assertTrue("the confirmation it needs is named: " + schema, schema.contains("Needs overwriteDiskEdits=true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnEmptyOrUnreadableListIsRefused()
    {
        List<List<String>> cases = new ArrayList<>();
        cases.add(null);
        cases.add(Collections.emptyList());
        for (List<String> objects : cases)
        {
            String error = ResyncToDiskTool.objectsGuardError(objects, false, false, false, true);
            assertNotNull(error);
            assertTrue(error, error.contains("objects is empty")); //$NON-NLS-1$
        }
    }

    @Test
    public void testEveryOtherWriteModeIsRefusedByName()
    {
        assertRefusedNaming(ResyncToDiskTool.objectsGuardError(ONE, true, false, false, true), "fullExport"); //$NON-NLS-1$
        assertRefusedNaming(ResyncToDiskTool.objectsGuardError(ONE, false, true, false, true),
            "cleanDanglingReferences"); //$NON-NLS-1$
        assertRefusedNaming(ResyncToDiskTool.objectsGuardError(ONE, false, false, true, true),
            "cleanOrphanRoleRights"); //$NON-NLS-1$
    }

    @Test
    public void testTheResaveNeedsTheOverwriteConfirmationAndNamesWhatItWouldRewrite()
    {
        String error = ResyncToDiskTool.objectsGuardError(ONE, false, false, false, false);
        assertNotNull(error);
        assertTrue(error, error.contains("overwriteDiskEdits=true")); //$NON-NLS-1$
        assertTrue(error, error.contains("Catalog.Goods.Form.ItemForm")); //$NON-NLS-1$
        assertTrue("it points at the check to run first: " + error, error.contains("validate_form_model")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAConfirmedResaveOfItsOwnPasses()
    {
        assertNull(ResyncToDiskTool.objectsGuardError(ONE, false, false, false, true));
    }

    // --- the address ----------------------------------------------------------------------------

    @Test
    public void testTheAddressReducesAContentFormFqnToItsFormInBothLanguages()
    {
        assertEquals("Catalog.Goods.Form.ItemForm", //$NON-NLS-1$
            ResyncToDiskTool.resaveAddress("Catalog.Goods.Form.ItemForm.Form")); //$NON-NLS-1$
        assertEquals("Catalog.Goods." + RU_FORM + ".ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
            ResyncToDiskTool.resaveAddress(RU_CATALOG + ".Goods." + RU_FORM + ".ItemForm." + RU_FORM)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonForm.Main", ResyncToDiskTool.resaveAddress("CommonForm.Main.Form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonForm.Main", ResyncToDiskTool.resaveAddress(RU_COMMON_FORM + ".Main." + RU_FORM)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheAddressLeavesEverythingElseToTheResolver()
    {
        assertEquals("Catalog.Goods", ResyncToDiskTool.resaveAddress(" " + RU_CATALOG + ".Goods ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a common form NAMED Form is a form, not a content-form FQN", //$NON-NLS-1$
            "CommonForm.Form", ResyncToDiskTool.resaveAddress("CommonForm.Form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("three parts that do not end in a form stay as given", //$NON-NLS-1$
            "Catalog.Goods.Form", ResyncToDiskTool.resaveAddress("Catalog.Goods.Form")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- the target -----------------------------------------------------------------------------

    @Test
    public void testAFormResolvesToItsContentFormInEnglishAndInRussian()
    {
        Model model = new Model();
        for (String requested : new String[] {
            "Catalog." + RU_NAME + ".Form.ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
            RU_CATALOG + "." + RU_NAME + "." + RU_FORM + ".ItemForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog." + RU_NAME + ".Form.ItemForm.Form", //$NON-NLS-1$ //$NON-NLS-2$
            RU_CATALOG + "." + RU_NAME + "." + RU_FORM + ".ItemForm." + RU_FORM }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertSame(requested, model.itemContent,
                ResyncToDiskTool.resolveResaveTarget(model.scope, model.noTopObjects(), requested));
        }
        for (String requested : new String[] { "CommonForm.Main", RU_COMMON_FORM + ".Main", "CommonForm.Main.Form" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertSame(requested, model.commonContent,
                ResyncToDiskTool.resolveResaveTarget(model.scope, model.noTopObjects(), requested));
        }
        assertTrue("a resolved form never falls back to the raw lookup: " + model.lookups, model.lookups.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAFormWithNoManagedContentIsRefusedByName()
    {
        Model model = new Model();
        Object refusal = ResyncToDiskTool.resolveResaveTarget(model.scope, model.noTopObjects(),
            "Catalog." + RU_NAME + ".Form.Legacy"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(refusal), refusal instanceof String
            && ((String)refusal).contains("Legacy") && ((String)refusal).contains("no managed content")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnUnknownFqnIsRefusedByNameAfterTheRawLookupMisses()
    {
        Model model = new Model();
        Object refusal = ResyncToDiskTool.resolveResaveTarget(model.scope, model.noTopObjects(),
            "Catalog." + RU_NAME + ".Form.Missing"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(refusal), refusal instanceof String
            && ((String)refusal).contains("Form.Missing") && ((String)refusal).contains("names no object")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Catalog." + RU_NAME + ".Form.Missing"), model.lookups); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnyOtherTopObjectFqnGoesToTheTransactionLookupInEnglish()
    {
        Model model = new Model();
        IBmObject rights = mock(IBmObject.class);
        Function<String, IBmObject> lookup = fqn -> "Role.Admin.Rights".equals(fqn) ? rights : null; //$NON-NLS-1$
        assertSame(rights, ResyncToDiskTool.resolveResaveTarget(model.scope, lookup, "Role.Admin.Rights")); //$NON-NLS-1$
    }

    @Test
    public void testAMemberIsRefusedAsStoredInItsOwnersFile()
    {
        Model model = new Model();
        Object refusal = ResyncToDiskTool.resolveResaveTarget(model.scope, model.noTopObjects(),
            RU_CATALOG + "." + RU_NAME + ".Реквизит.Weight"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(refusal), refusal instanceof String
            && ((String)refusal).contains("inside its owner's file")); //$NON-NLS-1$
        assertFalse("a member never reaches the raw lookup: " + model.lookups, //$NON-NLS-1$
            model.lookups.contains("Catalog." + RU_NAME + ".Attribute.Weight")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testABlankEntryIsRefused()
    {
        Model model = new Model();
        assertTrue(ResyncToDiskTool.resolveResaveTarget(model.scope, model.noTopObjects(), "  ") instanceof String); //$NON-NLS-1$
    }

    // --- the files after the export ---------------------------------------------------------------

    @Test
    public void testAFileIsWrittenWhenItIsNewerLargerOrNewAndOtherwiseUnchangedOrMissing() throws IOException
    {
        File dir = Files.createTempDirectory("resave-state").toFile(); //$NON-NLS-1$
        try
        {
            File file = new File(dir, "Form.form"); //$NON-NLS-1$
            assertEquals("missing", ResyncToDiskTool.fileState(file, 1000L, 10L)); //$NON-NLS-1$
            Files.write(file.toPath(), "0123456789".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertTrue(file.setLastModified(1_000_000L));
            assertEquals("written", ResyncToDiskTool.fileState(file, -1L, -1L)); //$NON-NLS-1$
            assertEquals("unchanged", ResyncToDiskTool.fileState(file, 1_000_000L, 10L)); //$NON-NLS-1$
            assertEquals("written", ResyncToDiskTool.fileState(file, 1_000_000L, 12L)); //$NON-NLS-1$
            assertEquals("written", ResyncToDiskTool.fileState(file, 999_000L, 10L)); //$NON-NLS-1$
        }
        finally
        {
            new File(dir, "Form.form").delete(); //$NON-NLS-1$
            dir.delete();
        }
    }

    @Test
    public void testSettlingReplacesTheTransitFieldsWithAStateAndSummarizes() throws IOException
    {
        File dir = Files.createTempDirectory("resave-settle").toFile(); //$NON-NLS-1$
        File form = new File(dir, "src/Catalogs/Goods/Forms/ItemForm/Form.form"); //$NON-NLS-1$
        try
        {
            form.getParentFile().mkdirs();
            Files.write(form.toPath(), "<form/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            JsonArray rows = new JsonArray();
            rows.add(row("src/Catalogs/Goods/Forms/ItemForm/Form.form", -1L, -1L)); //$NON-NLS-1$
            rows.add(row("src/Catalogs/Gone/Gone.mdo", 5L, 5L)); //$NON-NLS-1$
            JsonObject noFile = new JsonObject();
            noFile.addProperty("object", "Role.X.Rights"); //$NON-NLS-1$ //$NON-NLS-2$
            rows.add(noFile);

            String message = ResyncToDiskTool.settleResaved(rows, dir);

            assertEquals("written", state(rows, 0)); //$NON-NLS-1$
            assertEquals("missing", state(rows, 1)); //$NON-NLS-1$
            assertEquals("unknown", state(rows, 2)); //$NON-NLS-1$
            for (int i = 0; i < rows.size(); i++)
            {
                JsonObject row = rows.get(i).getAsJsonObject();
                assertFalse("transit fields never reach the caller: " + row, //$NON-NLS-1$
                    row.has("previousModified") || row.has("previousSize")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            assertTrue(message, message.startsWith("Re-saved 3 object(s) from the model: 1 file(s) rewritten")); //$NON-NLS-1$
            assertTrue(message, message.contains("1 missing after the export")); //$NON-NLS-1$
            assertTrue(message, message.contains("1 with no known file")); //$NON-NLS-1$
        }
        finally
        {
            form.delete();
            for (File d = form.getParentFile(); d != null && !d.equals(dir.getParentFile()); d = d.getParentFile())
            {
                d.delete();
            }
        }
    }

    private static JsonObject row(String file, long modified, long size)
    {
        JsonObject row = new JsonObject();
        row.addProperty("object", file); //$NON-NLS-1$
        row.addProperty("file", file); //$NON-NLS-1$
        row.addProperty("previousModified", modified); //$NON-NLS-1$
        row.addProperty("previousSize", size); //$NON-NLS-1$
        return row;
    }

    private static String state(JsonArray rows, int index)
    {
        return rows.get(index).getAsJsonObject().get("state").getAsString(); //$NON-NLS-1$
    }

    private static void assertRefusedNaming(String error, String flag)
    {
        assertNotNull(flag, error);
        assertTrue(error, error.contains("objects cannot be combined with " + flag)); //$NON-NLS-1$
    }

    /** A configuration with a Russian-named catalog holding a managed and a legacy form, and a common form. */
    private static final class Model
    {
        final MetadataScope scope;
        final Form itemContent = FormFactory.eINSTANCE.createForm();
        final Form commonContent = FormFactory.eINSTANCE.createForm();
        final List<String> lookups = new ArrayList<>();

        Model()
        {
            MdClassFactory f = MdClassFactory.eINSTANCE;
            Configuration config = f.createConfiguration();
            config.setName("Cfg"); //$NON-NLS-1$
            Catalog goods = f.createCatalog();
            goods.setName(RU_NAME);
            CatalogForm item = f.createCatalogForm();
            item.setName("ItemForm"); //$NON-NLS-1$
            item.setForm(itemContent);
            goods.getForms().add(item);
            CatalogForm legacy = f.createCatalogForm();
            legacy.setName("Legacy"); //$NON-NLS-1$
            goods.getForms().add(legacy);
            CatalogAttribute weight = f.createCatalogAttribute();
            weight.setName("Weight"); //$NON-NLS-1$
            goods.getAttributes().add(weight);
            config.getCatalogs().add(goods);
            CommonForm main = f.createCommonForm();
            main.setName("Main"); //$NON-NLS-1$
            main.setForm(commonContent);
            config.getCommonForms().add(main);
            scope = MetadataScope.ofConfiguration(config);
        }

        Function<String, IBmObject> noTopObjects()
        {
            return fqn -> {
                lookups.add(fqn);
                return null;
            };
        }
    }
}
