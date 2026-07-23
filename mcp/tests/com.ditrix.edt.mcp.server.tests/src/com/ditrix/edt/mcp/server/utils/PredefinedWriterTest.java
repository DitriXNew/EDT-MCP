/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Tests {@link PredefinedWriter} entirely in-memory (bare {@link MdClassFactory}-created
 * {@link Catalog} / {@link ChartOfCharacteristicTypes} objects, NO BM model / no live workbench):
 * FQN grammar parsing, the owner-type support gate, the {@code properties} parser's guard rules,
 * recursive find, create (top-level / into a folder / duplicate / parent errors), the {@code code}
 * value building (String vs Number {@code codeType}, strict JSON types, {@code codeLength}
 * rejection), modify (guards) and delete (leaf / folder-with-children, preview + confirm). The BM
 * write-transaction boundary and force-export around these pure operations are exercised by the
 * e2e suite against a live Catalog / ChartOfCharacteristicTypes.
 */
public class PredefinedWriterTest
{
    // ---- test fixtures ---------------------------------------------------------------------------

    private static Catalog newCatalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    private static Catalog newStringCodeCatalog(String name, int codeLength)
    {
        Catalog catalog = newCatalog(name);
        catalog.setCodeType(CatalogCodeType.STRING);
        catalog.setCodeLength(codeLength);
        return catalog;
    }

    private static Catalog newNumberCodeCatalog(String name, int codeLength)
    {
        Catalog catalog = newCatalog(name);
        catalog.setCodeType(CatalogCodeType.NUMBER);
        catalog.setCodeLength(codeLength);
        return catalog;
    }

    private static ChartOfCharacteristicTypes newCharacteristicTypes(String name, int codeLength)
    {
        ChartOfCharacteristicTypes types = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        types.setName(name);
        types.setCodeLength(codeLength);
        return types;
    }

    private static JsonObject prop(String name, com.google.gson.JsonElement value)
    {
        JsonObject o = new JsonObject();
        o.addProperty("name", name); //$NON-NLS-1$
        if (value != null)
        {
            o.add("value", value); //$NON-NLS-1$
        }
        return o;
    }

    private static JsonObject stringProp(String name, String value)
    {
        return prop(name, new JsonPrimitive(value));
    }

    private static JsonObject boolProp(String name, boolean value)
    {
        return prop(name, new JsonPrimitive(value));
    }

    private static JsonObject numberProp(String name, Number value)
    {
        return prop(name, new JsonPrimitive(value));
    }

    private static PredefinedWriter.ItemProps parse(List<JsonObject> properties, boolean isModify)
    {
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(properties, isModify, props);
        assertNull("parseProperties must succeed: " + err, err); //$NON-NLS-1$
        return props;
    }

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    /** A minimal {@code {types:[{kind}]}} value-type spec, the shape {@code valueType}/{@code type} takes. */
    private static JsonObject typeSpec(String kind)
    {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind); //$NON-NLS-1$
        JsonArray types = new JsonArray();
        types.add(item);
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        return spec;
    }

    // ==================== parseRef (FQN grammar) ====================

    @Test
    public void testParseRefRecognizesCatalog()
    {
        PredefinedWriter.PredefinedRef ref = PredefinedWriter.parseRef("Catalog.Products.Predefined.Blue"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("Catalog", ref.ownerType); //$NON-NLS-1$
        assertEquals("Products", ref.ownerName); //$NON-NLS-1$
        assertEquals("Blue", ref.itemName); //$NON-NLS-1$
        assertEquals("Catalog.Products", ref.ownerFqn()); //$NON-NLS-1$
    }

    @Test
    public void testParseRefRecognizesChartOfCharacteristicTypes()
    {
        PredefinedWriter.PredefinedRef ref =
            PredefinedWriter.parseRef("ChartOfCharacteristicTypes.Properties.Predefined.Weight"); //$NON-NLS-1$
        assertNotNull(ref);
        assertEquals("ChartOfCharacteristicTypes", ref.ownerType); //$NON-NLS-1$
        assertEquals("Properties", ref.ownerName); //$NON-NLS-1$
        assertEquals("Weight", ref.itemName); //$NON-NLS-1$
    }

    @Test
    public void testParseRefAcceptsRussianPredefinedToken()
    {
        // "Предопределенные" - the yo-normalized ('е') spelling of the predefined-items node.
        String ruPredefined = fromCp(0x041f, 0x0440, 0x0435, 0x0434, 0x043e, 0x043f, 0x0440, 0x0435, 0x0434,
            0x0435, 0x043b, 0x0435, 0x043d, 0x043d, 0x044b, 0x0435);
        PredefinedWriter.PredefinedRef ref =
            PredefinedWriter.parseRef("Catalog.Products." + ruPredefined + ".Blue"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the Russian Predefined token must be recognized", ref); //$NON-NLS-1$
        assertEquals("Blue", ref.itemName); //$NON-NLS-1$
    }

    @Test
    public void testParseRefAcceptsYoSpelledRussianPredefinedToken()
    {
        // "Предопределённые" - the NATURAL 'ё' spelling a Russian caller types (index 11 is 'ё',
        // U+0451, where the normalized constant carries 'е'). It must be recognized too - otherwise
        // parseRef returns null and the predefined branch is silently skipped.
        String ruPredefinedYo = fromCp(0x041f, 0x0440, 0x0435, 0x0434, 0x043e, 0x043f, 0x0440, 0x0435,
            0x0434, 0x0435, 0x043b, 0x0451, 0x043d, 0x043d, 0x044b, 0x0435);
        PredefinedWriter.PredefinedRef ref =
            PredefinedWriter.parseRef("Catalog.Products." + ruPredefinedYo + ".Blue"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the 'yo'-spelled Russian Predefined token must be recognized", ref); //$NON-NLS-1$
        assertEquals("Blue", ref.itemName); //$NON-NLS-1$
    }

    @Test
    public void testParseRefRejectsMisplacedYoInRussianToken()
    {
        // "Прёдопределенные" - a MISSPELLING with 'ё' at index 2 (not the valid index 11). Only the two
        // real spellings are accepted; a blanket yo-normalization would wrongly classify this as the
        // predefined kind token and route the FQN into the predefined dispatch branch.
        String misplaced = fromCp(0x041f, 0x0440, 0x0451, 0x0434, 0x043e, 0x043f, 0x0440, 0x0435, 0x0434,
            0x0435, 0x043b, 0x0435, 0x043d, 0x043d, 0x044b, 0x0435);
        assertNull("a 'ё' misplaced onto a different 'е' position must NOT be recognized", //$NON-NLS-1$
            PredefinedWriter.parseRef("Catalog.Products." + misplaced + ".Blue")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseRefIsCaseInsensitive()
    {
        assertNotNull(PredefinedWriter.parseRef("Catalog.Products.PREDEFINED.Blue")); //$NON-NLS-1$
        assertNotNull(PredefinedWriter.parseRef("Catalog.Products.predefined.Blue")); //$NON-NLS-1$
    }

    @Test
    public void testParseRefRejectsWrongArity()
    {
        assertNull(PredefinedWriter.parseRef("Catalog.Products")); //$NON-NLS-1$
        assertNull(PredefinedWriter.parseRef("Catalog.Products.Predefined.Blue.Extra")); //$NON-NLS-1$
        assertNull(PredefinedWriter.parseRef(null));
        assertNull(PredefinedWriter.parseRef("")); //$NON-NLS-1$
    }

    @Test
    public void testParseRefRejectsNonPredefinedKindToken()
    {
        // A normal mdclass member FQN (Attribute at position 2) must NOT be misread as a predefined item.
        assertNull(PredefinedWriter.parseRef("Catalog.Products.Attribute.Weight")); //$NON-NLS-1$
    }

    // ==================== owner-type support gate ====================

    @Test
    public void testUnsupportedOwnerTypeErrorNullForSupportedOwners()
    {
        assertNull(PredefinedWriter.unsupportedOwnerTypeError("Catalog")); //$NON-NLS-1$
        assertNull(PredefinedWriter.unsupportedOwnerTypeError("ChartOfCharacteristicTypes")); //$NON-NLS-1$
        // Bilingual: the Russian Catalog token resolves the same way (only the TYPE token is bilingual).
        String ruCatalog = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d, 0x0438, 0x043a);
        assertNull(PredefinedWriter.unsupportedOwnerTypeError(ruCatalog));
    }

    @Test
    public void testUnsupportedOwnerTypeErrorDefersChartOfAccounts()
    {
        String err = PredefinedWriter.unsupportedOwnerTypeError("ChartOfAccounts"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("must say not yet supported", err.contains("not yet supported")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the richer per-item model", err.contains("AccountType")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnsupportedOwnerTypeErrorDefersChartOfCalculationTypes()
    {
        String err = PredefinedWriter.unsupportedOwnerTypeError("ChartOfCalculationTypes"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue(err.contains("not yet supported")); //$NON-NLS-1$
        assertTrue("must name the base/displaced model", err.contains("displaced")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnsupportedOwnerTypeErrorRejectsOtherKnownType()
    {
        // 'Document.X.Predefined.Y' - a recognized metadata type that simply has no predefined items.
        String err = PredefinedWriter.unsupportedOwnerTypeError("Document"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("must say it does not have predefined items", //$NON-NLS-1$
            err.contains("does not have predefined items")); //$NON-NLS-1$
    }

    @Test
    public void testUnsupportedOwnerTypeErrorRejectsUnknownType()
    {
        String err = PredefinedWriter.unsupportedOwnerTypeError("NotAType"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue("must echo the bad token", err.contains("NotAType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(err.contains("Unknown metadata type")); //$NON-NLS-1$
    }

    // ==================== properties parsing ====================

    @Test
    public void testParsePropertiesDescription()
    {
        PredefinedWriter.ItemProps props = parse(List.of(stringProp("description", "Blue color")), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(props.descriptionSet);
        assertEquals("Blue color", props.description); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesDescriptionRejectsNonString()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(numberProp("description", 5)), false, out); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue(err.contains("must be a JSON string")); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesCodeCapturesRawElement()
    {
        PredefinedWriter.ItemProps props = parse(List.of(stringProp("code", "00007")), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(props.codeSet);
        assertEquals("00007", props.code.getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesIsFolderStrictBoolean()
    {
        PredefinedWriter.ItemProps props = parse(List.of(boolProp("isFolder", true)), false); //$NON-NLS-1$
        assertTrue(props.isFolderSet);
        assertEquals(Boolean.TRUE, props.isFolder);

        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(stringProp("isFolder", "true")), false, out); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a JSON string 'true' must be rejected for isFolder (strict boolean)", err); //$NON-NLS-1$
        assertTrue(err.contains("JSON boolean")); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesParentAllowedOnCreateRefusedOnModify()
    {
        PredefinedWriter.ItemProps createProps = parse(List.of(stringProp("parent", "Colors")), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Colors", createProps.parentName); //$NON-NLS-1$

        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(stringProp("parent", "Colors")), true, out); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("moving to a different parent must be refused on modify", err); //$NON-NLS-1$
        assertTrue(err.contains("not yet supported")); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesNameAlwaysRefused()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String errCreate = PredefinedWriter.parseProperties(List.of(stringProp("name", "NewName")), false, //$NON-NLS-1$ //$NON-NLS-2$
            out);
        assertNotNull(errCreate);
        assertTrue(errCreate.contains("Renaming a predefined item is not supported")); //$NON-NLS-1$

        String errModify = PredefinedWriter.parseProperties(List.of(stringProp("name", "NewName")), true, //$NON-NLS-1$ //$NON-NLS-2$
            out);
        assertNotNull(errModify);
        assertTrue(errModify.contains("Renaming a predefined item is not supported")); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesUnknownPropertyRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(stringProp("bogus", "x")), false, out); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(err);
        assertTrue(err.contains("'bogus'")); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesEmptyNameRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        JsonObject entry = new JsonObject();
        entry.add("value", new JsonPrimitive("x")); //$NON-NLS-1$ //$NON-NLS-2$
        String err = PredefinedWriter.parseProperties(List.of(entry), false, out);
        assertNotNull(err);
        assertTrue(err.contains("non-empty 'name'")); //$NON-NLS-1$
    }

    // ==================== create: top-level, into a folder, duplicates, parent errors ====================

    @Test
    public void testCreateTopLevelSetsIdAndDefaultsDescriptionToName()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$

        assertFalse(result.isError());
        assertNotNull("id must be assigned (MANDATORY)", result.item.getId()); //$NON-NLS-1$
        assertEquals("Blue", result.item.getName()); //$NON-NLS-1$
        assertEquals("description defaults to the name when omitted", "Blue", result.item.getDescription()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("code omitted -> left UNSET, never invented", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$

        PredefinedItem found = PredefinedWriter.findByName(catalog, "blue"); //$NON-NLS-1$
        assertNotNull("find is case-insensitive", found); //$NON-NLS-1$
        assertEquals(result.item, found);
    }

    @Test
    public void testCreateWithExplicitDescriptionAndIsFolder()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.description = "All colors"; //$NON-NLS-1$
        props.descriptionSet = true;
        props.isFolder = true;
        props.isFolderSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Group", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals("All colors", result.item.getDescription()); //$NON-NLS-1$
        assertTrue(PredefinedWriter.isFolder(result.item));
    }

    @Test
    public void testCreateIntoFolderAppendsToFolderContent()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps childProps = new PredefinedWriter.ItemProps();
        childProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.WriteResult child = PredefinedWriter.create(catalog, "Red", childProps, false); //$NON-NLS-1$

        assertFalse(child.isError());
        PredefinedWriter.ItemLookup lookup = PredefinedWriter.lookup(catalog, "Red"); //$NON-NLS-1$
        assertNotNull(lookup);
        assertEquals("Warm", lookup.parentName); //$NON-NLS-1$
        // Recursive find reaches the nested child directly by name, without needing its parent path.
        assertNotNull(PredefinedWriter.findByName(catalog, "Red")); //$NON-NLS-1$
    }

    @Test
    public void testCreateDuplicateExactIsRejected()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        // Lower-case 'blue' must be caught too (the check is case-insensitive).
        PredefinedWriter.WriteResult dup =
            PredefinedWriter.create(catalog, "blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertTrue(dup.isError());
        assertTrue(dup.error.contains("already exists")); //$NON-NLS-1$
    }

    @Test
    public void testCreateDuplicateWithExpectedNotExistsReportsStaleSnapshot()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.WriteResult dup =
            PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), true); //$NON-NLS-1$
        assertTrue(dup.isError());
        assertTrue(dup.error.contains("Precondition failed")); //$NON-NLS-1$
        assertTrue(dup.error.contains("stale")); //$NON-NLS-1$
    }

    @Test
    public void testCreateYoVariantIsNotADuplicate()
    {
        // "Мёд" (yo) vs "Мед" (ye) - the duplicate check is EXACT (no yo-fallback, #291 lesson): these
        // are different strings, so creating the second must NOT be rejected as a duplicate of the first.
        Catalog catalog = newCatalog("Foods"); //$NON-NLS-1$
        String yo = fromCp(0x041c, 0x0451, 0x0434); // Мёд
        String ye = fromCp(0x041c, 0x0435, 0x0434); // Мед
        PredefinedWriter.WriteResult first = PredefinedWriter.create(catalog, yo, new PredefinedWriter.ItemProps(),
            false);
        PredefinedWriter.WriteResult second = PredefinedWriter.create(catalog, ye,
            new PredefinedWriter.ItemProps(), false);

        assertFalse(first.isError());
        assertFalse("a yo/ye spelling variant must NOT be treated as a duplicate", second.isError()); //$NON-NLS-1$
        assertNotNull(PredefinedWriter.findByName(catalog, yo));
        assertNotNull(PredefinedWriter.findByName(catalog, ye));
    }

    @Test
    public void testCreateParentNotFoundIsRejected()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.parentName = "NoSuchFolder"; //$NON-NLS-1$
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("Parent predefined item (folder) not found")); //$NON-NLS-1$
    }

    @Test
    public void testCreateParentNotAFolderIsRejected()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        // "Blue" is a plain (non-folder) item.
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.parentName = "Blue"; //$NON-NLS-1$
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Navy", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("is not a folder")); //$NON-NLS-1$
    }

    @Test
    public void testCreateOnUnsupportedOwnerObjectIsRejected()
    {
        Document doc = MdClassFactory.eINSTANCE.createDocument();
        doc.setName("Order"); //$NON-NLS-1$
        PredefinedWriter.WriteResult result =
            PredefinedWriter.create(doc, "X", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("does not support predefined items")); //$NON-NLS-1$
    }

    // ==================== code value building ====================

    @Test
    public void testCatalogStringCodeBuildsStringValue()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("00001"); //$NON-NLS-1$
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals("00001", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    @Test
    public void testCatalogStringCodeTooLongIsRejected()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 3); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("TooLong"); //$NON-NLS-1$
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("codeLength")); //$NON-NLS-1$
    }

    @Test
    public void testCatalogStringCodeRejectsJsonNumber()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(42);
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("must be a JSON string")); //$NON-NLS-1$
    }

    @Test
    public void testCatalogNumberCodeBuildsNumberValue()
    {
        Catalog catalog = newNumberCodeCatalog("Colors", 4); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(42);
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals("42", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    @Test
    public void testCatalogNumberCodeTooManyDigitsIsRejected()
    {
        Catalog catalog = newNumberCodeCatalog("Colors", 2); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(123);
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("codeLength")); //$NON-NLS-1$
    }

    @Test
    public void testCatalogNumberCodeRejectsJsonString()
    {
        Catalog catalog = newNumberCodeCatalog("Colors", 4); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("42"); //$NON-NLS-1$
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("must be a JSON number")); //$NON-NLS-1$
    }

    @Test
    public void testCharacteristicTypesCodeIsPlainString()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("W001"); //$NON-NLS-1$
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Weight", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals("W001", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    @Test
    public void testCharacteristicTypesCodeRejectsJsonNumber()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(1);
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Weight", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("must be a JSON string")); //$NON-NLS-1$
    }

    @Test
    public void testCharacteristicTypesCodeTooLongIsRejected()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 2); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("TooLong"); //$NON-NLS-1$
        props.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Weight", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("codeLength")); //$NON-NLS-1$
    }

    // ==================== modify ====================

    @Test
    public void testModifyDescriptionAndCode()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.description = "Sky blue"; //$NON-NLS-1$
        mod.descriptionSet = true;
        mod.code = new JsonPrimitive("00099"); //$NON-NLS-1$
        mod.codeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "Blue", mod); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals("Sky blue", result.item.getDescription()); //$NON-NLS-1$
        assertEquals("00099", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    @Test
    public void testModifyCodeToJsonNullClearsIt()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps createProps = new PredefinedWriter.ItemProps();
        createProps.code = new JsonPrimitive("00001"); //$NON-NLS-1$
        createProps.codeSet = true;
        PredefinedWriter.create(catalog, "Blue", createProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.code = JsonNull.INSTANCE;
        mod.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "Blue", mod); //$NON-NLS-1$
        assertFalse(result.isError());
        assertNull(PredefinedWriter.displayCode(result.item));
    }

    @Test
    public void testModifyFolderToItemRejectedWhenChildrenExist()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$
        PredefinedWriter.ItemProps childProps = new PredefinedWriter.ItemProps();
        childProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Red", childProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.isFolder = false;
        mod.isFolderSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "Warm", mod); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("child item(s)")); //$NON-NLS-1$
        // The rejection must not have applied the flag.
        assertTrue(PredefinedWriter.isFolder(PredefinedWriter.findByName(catalog, "Warm"))); //$NON-NLS-1$
    }

    @Test
    public void testModifyFolderToItemAllowedWhenEmpty()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Empty", folderProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.isFolder = false;
        mod.isFolderSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "Empty", mod); //$NON-NLS-1$
        assertFalse(result.isError());
        assertFalse(PredefinedWriter.isFolder(result.item));
    }

    @Test
    public void testModifyNotFoundReturnsError()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.descriptionSet = true;
        mod.description = "x"; //$NON-NLS-1$
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "NoSuchItem", mod); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("not found")); //$NON-NLS-1$
    }

    // ==================== delete: preview + confirm, leaf + folder cascade ====================

    @Test
    public void testDeletePreviewLeafItem()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.DeletePreview preview = PredefinedWriter.preview(catalog, "Blue"); //$NON-NLS-1$
        assertTrue(preview.found);
        assertFalse(preview.isFolder);
        assertEquals(0, preview.descendantCount);
    }

    @Test
    public void testDeletePreviewFolderCountsAllDescendants()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps nestedFolderProps = new PredefinedWriter.ItemProps();
        nestedFolderProps.isFolder = true;
        nestedFolderProps.isFolderSet = true;
        nestedFolderProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Reds", nestedFolderProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps leafProps = new PredefinedWriter.ItemProps();
        leafProps.parentName = "Reds"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Crimson", leafProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps directChildProps = new PredefinedWriter.ItemProps();
        directChildProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Orange", directChildProps, false); //$NON-NLS-1$

        // Warm -> Reds -> Crimson, and Warm -> Orange: 3 descendants total.
        PredefinedWriter.DeletePreview preview = PredefinedWriter.preview(catalog, "Warm"); //$NON-NLS-1$
        assertTrue(preview.found);
        assertTrue(preview.isFolder);
        assertEquals(3, preview.descendantCount);
    }

    @Test
    public void testDeletePreviewNotFound()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.DeletePreview preview = PredefinedWriter.preview(catalog, "NoSuchItem"); //$NON-NLS-1$
        assertFalse(preview.found);
    }

    @Test
    public void testDeleteLeafRemovesFromContainerList()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Red", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.WriteResult result = PredefinedWriter.delete(catalog, "Blue"); //$NON-NLS-1$
        assertFalse(result.isError());
        assertNull(PredefinedWriter.findByName(catalog, "Blue")); //$NON-NLS-1$
        assertNotNull("the sibling must survive", PredefinedWriter.findByName(catalog, "Red")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDeleteFolderCascadesChildren()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$
        PredefinedWriter.ItemProps childProps = new PredefinedWriter.ItemProps();
        childProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Red", childProps, false); //$NON-NLS-1$

        PredefinedWriter.WriteResult result = PredefinedWriter.delete(catalog, "Warm"); //$NON-NLS-1$
        assertFalse(result.isError());
        assertNull(PredefinedWriter.findByName(catalog, "Warm")); //$NON-NLS-1$
        assertNull("a folder delete must cascade its children", //$NON-NLS-1$
            PredefinedWriter.findByName(catalog, "Red")); //$NON-NLS-1$
    }

    @Test
    public void testDeleteNotFoundReturnsError()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.WriteResult result = PredefinedWriter.delete(catalog, "NoSuchItem"); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("not found")); //$NON-NLS-1$
    }

    // ==================== listAll (get_metadata_details owner-level rendering) ====================

    @Test
    public void testListAllFlattensItemsAndContentInDocumentOrder()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$
        PredefinedWriter.ItemProps childProps = new PredefinedWriter.ItemProps();
        childProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Red", childProps, false); //$NON-NLS-1$

        List<PredefinedWriter.ItemRow> rows = PredefinedWriter.listAll(catalog);
        assertEquals(3, rows.size());
        assertEquals("Blue", rows.get(0).name); //$NON-NLS-1$
        assertEquals(0, rows.get(0).depth);
        assertNull(rows.get(0).parentName);
        assertEquals("Warm", rows.get(1).name); //$NON-NLS-1$
        assertTrue(rows.get(1).isFolder);
        assertEquals("Red", rows.get(2).name); //$NON-NLS-1$
        assertEquals(1, rows.get(2).depth);
        assertEquals("Warm", rows.get(2).parentName); //$NON-NLS-1$
    }

    @Test
    public void testListAllEmptyWhenNoPredefinedContent()
    {
        Catalog catalog = newCatalog("Empty"); //$NON-NLS-1$
        assertTrue(PredefinedWriter.listAll(catalog).isEmpty());
    }

    // ---- a helper regression: countDescendants on a leaf item is 0, on a folder tallies the subtree ----

    @Test
    public void testCountDescendantsOfLeafIsZero()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.WriteResult leaf =
            PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertEquals(0, PredefinedWriter.countDescendants(leaf.item));
    }

    // ==================== strict-value hardening (codex round 1) ====================

    /** On CREATE, a 'code' entry with NO 'value' key is malformed - there is nothing to clear (a
     *  missing/null value on MODIFY instead clears; see testModifyCodeMissingValueClearsIt). */
    @Test
    public void testParsePropertiesCodeWithoutValueRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(prop("code", null)), false, out); //$NON-NLS-1$
        assertNotNull("a 'code' entry with no 'value' must be rejected, not treated as a clear", err); //$NON-NLS-1$
        assertTrue(err.contains("needs a 'value'")); //$NON-NLS-1$
        assertFalse(out.codeSet);
    }

    /** A 'description' with a missing or JSON-null value is a type error, never an implicit default. */
    @Test
    public void testParsePropertiesDescriptionMissingOrNullValueRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String errMissing = PredefinedWriter.parseProperties(List.of(prop("description", null)), false, out); //$NON-NLS-1$
        assertNotNull(errMissing);
        assertTrue(errMissing.contains("must be a JSON string")); //$NON-NLS-1$

        String errNull =
            PredefinedWriter.parseProperties(List.of(prop("description", JsonNull.INSTANCE)), false, out); //$NON-NLS-1$
        assertNotNull(errNull);
        assertTrue(errNull.contains("must be a JSON string")); //$NON-NLS-1$
    }

    /** 'parent' must be a non-empty JSON string - a number/null/empty value is a type error. */
    @Test
    public void testParsePropertiesParentStrictString()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String errNumber = PredefinedWriter.parseProperties(List.of(numberProp("parent", 5)), false, out); //$NON-NLS-1$
        assertNotNull("a numeric 'parent' must be rejected, not string-coerced", errNumber); //$NON-NLS-1$
        assertTrue(errNumber.contains("non-empty JSON string")); //$NON-NLS-1$

        String errNull =
            PredefinedWriter.parseProperties(List.of(prop("parent", JsonNull.INSTANCE)), false, out); //$NON-NLS-1$
        assertNotNull("a JSON-null 'parent' must be rejected, not silently top-level", errNull); //$NON-NLS-1$

        String errEmpty = PredefinedWriter.parseProperties(List.of(stringProp("parent", " ")), false, out); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a blank 'parent' must be rejected (omit it for a top-level item)", errEmpty); //$NON-NLS-1$
    }

    /** An explicitly supplied EMPTY description is honored, on create and on modify - only an
     *  OMITTED description defaults to the Name. */
    @Test
    public void testExplicitEmptyDescriptionHonored()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps createProps = new PredefinedWriter.ItemProps();
        createProps.description = ""; //$NON-NLS-1$
        createProps.descriptionSet = true;
        PredefinedWriter.WriteResult created =
            PredefinedWriter.create(catalog, "Blue", createProps, false); //$NON-NLS-1$
        assertFalse(created.isError());
        assertEquals("", created.item.getDescription()); //$NON-NLS-1$

        PredefinedWriter.ItemProps modBack = new PredefinedWriter.ItemProps();
        modBack.description = "Sky"; //$NON-NLS-1$
        modBack.descriptionSet = true;
        PredefinedWriter.modify(catalog, "Blue", modBack); //$NON-NLS-1$

        PredefinedWriter.ItemProps modEmpty = new PredefinedWriter.ItemProps();
        modEmpty.description = ""; //$NON-NLS-1$
        modEmpty.descriptionSet = true;
        PredefinedWriter.WriteResult modified = PredefinedWriter.modify(catalog, "Blue", modEmpty); //$NON-NLS-1$
        assertFalse(modified.isError());
        assertEquals("", modified.item.getDescription()); //$NON-NLS-1$
    }

    // ==================== numeric-code hardening: no materialization, integer-only ====================

    /** An absurd exponent (1e100000000) is rejected via digit-count MATH - the value is never
     *  expanded (toBigInteger/toPlainString would turn this tiny request into a gigabyte). Completes
     *  instantly on both a bounded and an unlimited (codeLength=0) catalog. */
    @Test
    public void testNumericCodeHugeExponentRejectedWithoutMaterializing()
    {
        JsonPrimitive huge = new JsonPrimitive(new BigDecimal("1E+100000000")); //$NON-NLS-1$
        for (int codeLength : new int[] { 5, 0 })
        {
            Catalog catalog = newNumberCodeCatalog("Codes" + codeLength, codeLength); //$NON-NLS-1$
            PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
            props.code = huge;
            props.codeSet = true;
            PredefinedWriter.WriteResult result =
                PredefinedWriter.create(catalog, "Big" + codeLength, props, false); //$NON-NLS-1$
            assertTrue("codeLength=" + codeLength + " must reject the huge exponent", result.isError()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result.error.contains("digit(s)")); //$NON-NLS-1$
        }
    }

    /** A fractional numeric code is rejected - a catalog code is an integer. */
    @Test
    public void testNumericCodeFractionalRejected()
    {
        Catalog catalog = newNumberCodeCatalog("Codes", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(new BigDecimal("1.5")); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Half", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("fractional")); //$NON-NLS-1$
    }

    /** A negative numeric code is rejected. */
    @Test
    public void testNumericCodeNegativeRejected()
    {
        Catalog catalog = newNumberCodeCatalog("Codes", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(new BigDecimal("-7")); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Neg", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("negative")); //$NON-NLS-1$
    }

    // ==================== iterative walks: deep nesting must not overflow the stack ====================

    /** A 10000-deep hand-built chain is walked ITERATIVELY by find/list/count - no recursion, no
     *  {@link StackOverflowError} (a recursive walk overflows at roughly this depth). */
    @Test
    public void testDeepNestingWalksIteratively()
    {
        Catalog catalog = newStringCodeCatalog("Deep", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.WriteResult root = PredefinedWriter.create(catalog, "N0", folderProps, false); //$NON-NLS-1$
        CatalogPredefinedItem cursor = (CatalogPredefinedItem)root.item;
        for (int i = 1; i <= 10_000; i++)
        {
            CatalogPredefinedItem next = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
            next.setName("N" + i); //$NON-NLS-1$
            next.setIsFolder(true);
            cursor.getContent().add(next);
            cursor = next;
        }

        assertNotNull(PredefinedWriter.findByName(catalog, "N10000")); //$NON-NLS-1$
        assertEquals(10_001, PredefinedWriter.listAll(catalog).size());
        assertEquals(10_000, PredefinedWriter.countDescendants(root.item));
        assertEquals(10_000, PredefinedWriter.preview(catalog, "N0").descendantCount); //$NON-NLS-1$
    }

    /** The delete preview lists EVERY cascaded descendant as a {name, kind} row, in DOCUMENT
     *  (depth-first, pre-order) order. The fixture BRANCHES - a nested grandchild followed by a
     *  second direct child - so a breadth-first walk (Reds, Blue, Crimson) or sibling reordering
     *  would fail the exact sequence asserted here; a single chain could not tell them apart. */
    @Test
    public void testPreviewListsCascadedDescendantsDepthFirst()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$
        PredefinedWriter.ItemProps subFolderProps = new PredefinedWriter.ItemProps();
        subFolderProps.isFolder = true;
        subFolderProps.isFolderSet = true;
        subFolderProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Reds", subFolderProps, false); //$NON-NLS-1$
        PredefinedWriter.ItemProps leafProps = new PredefinedWriter.ItemProps();
        leafProps.parentName = "Reds"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Crimson", leafProps, false); //$NON-NLS-1$
        PredefinedWriter.ItemProps secondChildProps = new PredefinedWriter.ItemProps();
        secondChildProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", secondChildProps, false); //$NON-NLS-1$

        PredefinedWriter.DeletePreview preview = PredefinedWriter.preview(catalog, "Warm"); //$NON-NLS-1$
        assertEquals(3, preview.descendants.size());
        assertEquals("Reds", preview.descendants.get(0)[0]); //$NON-NLS-1$
        assertEquals("CatalogPredefinedItem", preview.descendants.get(0)[1]); //$NON-NLS-1$
        assertEquals("Crimson", preview.descendants.get(1)[0]); //$NON-NLS-1$
        assertEquals("Blue", preview.descendants.get(2)[0]); //$NON-NLS-1$
    }

    /** A JSON-null 'code' at CREATE is refused - there is nothing to clear (null-clearing is a
     *  modify_metadata concept); omitting the property is the way to leave the code unset. */
    @Test
    public void testCreateCodeJsonNullRejected()
    {
        Catalog catalog = newStringCodeCatalog("Colors", 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = JsonNull.INSTANCE;
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("cannot be JSON null at create")); //$NON-NLS-1$
        assertNull("the refused create must not leave a half-attached item", //$NON-NLS-1$
            PredefinedWriter.findByName(catalog, "Blue")); //$NON-NLS-1$
    }

    /** A ZERO code with a pathological positive scale (0e-1000000000) is stored NORMALIZED - the
     *  display code renders "0", never a billion-character plain-string expansion. */
    @Test
    public void testNumericCodeZeroWithHugeScaleStoredNormalized()
    {
        Catalog catalog = newNumberCodeCatalog("Codes", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(new BigDecimal("0e-1000000000")); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Zero", props, false); //$NON-NLS-1$
        assertFalse("an integer-valued zero must be accepted: " + result.error, result.isError()); //$NON-NLS-1$
        assertEquals("0", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    // ==================== yo-fallback lookup (codex round on #296) ====================
    // create_metadata normalizes a new item's Name 'ё'->'е'; the writer's READ/MODIFY/DELETE lookups
    // must then find the stored (normalized) item when a caller re-types the original 'ё' spelling.
    // Fixtures store the NORMALIZED ('е') Name (as the tool would) and look it up with the 'ё' spelling.

    /** findByName / lookup resolve a stored 'е'-name item when queried with the original 'ё' spelling. */
    @Test
    public void testLookupYoFallbackFindsNormalizedItem()
    {
        Catalog catalog = newStringCodeCatalog("Foods", 9); //$NON-NLS-1$
        String ye = fromCp(0x041c, 0x0435, 0x0434); // "Мед" - the stored, normalized Name
        String yo = fromCp(0x041c, 0x0451, 0x0434); // "Мёд" - the caller's original spelling
        PredefinedWriter.create(catalog, ye, new PredefinedWriter.ItemProps(), false);

        assertNotNull("exact spelling must resolve", PredefinedWriter.findByName(catalog, ye)); //$NON-NLS-1$
        assertNotNull("the original 'yo' spelling must resolve via the fallback", //$NON-NLS-1$
            PredefinedWriter.findByName(catalog, yo));
        assertNotNull("lookup() must apply the same fallback", PredefinedWriter.lookup(catalog, yo)); //$NON-NLS-1$
    }

    /** modify addressed by the original 'ё' spelling mutates the stored 'е'-name item. */
    @Test
    public void testModifyYoFallbackHitsNormalizedItem()
    {
        Catalog catalog = newStringCodeCatalog("Foods", 9); //$NON-NLS-1$
        String ye = fromCp(0x041c, 0x0435, 0x0434); // "Мед"
        String yo = fromCp(0x041c, 0x0451, 0x0434); // "Мёд"
        PredefinedWriter.create(catalog, ye, new PredefinedWriter.ItemProps(), false);

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.description = "honey"; //$NON-NLS-1$
        mod.descriptionSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, yo, mod);
        assertFalse("modify via the 'yo' spelling must hit the stored item, not fail not-found", //$NON-NLS-1$
            result.isError());
        assertEquals("honey", result.item.getDescription()); //$NON-NLS-1$
        assertEquals("the item's Name is unchanged (identity)", ye, result.item.getName()); //$NON-NLS-1$
    }

    /** preview + delete addressed by the original 'ё' spelling target the stored 'е'-name item. */
    @Test
    public void testDeleteYoFallbackHitsNormalizedItem()
    {
        Catalog catalog = newStringCodeCatalog("Foods", 9); //$NON-NLS-1$
        String ye = fromCp(0x041c, 0x0435, 0x0434); // "Мед"
        String yo = fromCp(0x041c, 0x0451, 0x0434); // "Мёд"
        PredefinedWriter.create(catalog, ye, new PredefinedWriter.ItemProps(), false);

        assertTrue("preview via the 'yo' spelling must find the item", //$NON-NLS-1$
            PredefinedWriter.preview(catalog, yo).found);
        PredefinedWriter.WriteResult result = PredefinedWriter.delete(catalog, yo);
        assertFalse("delete via the 'yo' spelling must remove the stored item", result.isError()); //$NON-NLS-1$
        assertNull("the item is gone", PredefinedWriter.findByName(catalog, ye)); //$NON-NLS-1$
    }

    /** A create-time 'parent' given in the original 'ё' spelling resolves a stored 'е'-name folder. */
    @Test
    public void testCreateParentYoFallbackResolvesNormalizedFolder()
    {
        Catalog catalog = newStringCodeCatalog("Foods", 9); //$NON-NLS-1$
        String yeFolder = fromCp(0x041c, 0x0435, 0x0434); // "Мед" - the stored, normalized folder Name
        String yoFolder = fromCp(0x041c, 0x0451, 0x0434); // "Мёд" - the caller's original spelling
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.create(catalog, yeFolder, folderProps, false);

        PredefinedWriter.ItemProps child = new PredefinedWriter.ItemProps();
        child.parentName = yoFolder; // the 'yo' spelling of the parent
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Honey", child, false); //$NON-NLS-1$
        assertFalse("parent given in the 'yo' spelling must resolve the normalized folder", //$NON-NLS-1$
            result.isError());
        PredefinedWriter.ItemLookup lookup = PredefinedWriter.lookup(catalog, "Honey"); //$NON-NLS-1$
        assertEquals("the child nests under the resolved folder", yeFolder, lookup.parentName); //$NON-NLS-1$
    }

    /** The create-time DUPLICATE check stays EXACT: the writer does NOT yo-fold it (the tool normalizes
     *  the incoming Name first, so 'ё'/'е' collision is decided BEFORE the writer; with normalizeYo=false
     *  a caller may author distinct yo-variant names - the #291 lesson). */
    @Test
    public void testCreateDuplicateCheckStaysExactAcrossYo()
    {
        Catalog catalog = newStringCodeCatalog("Foods", 9); //$NON-NLS-1$
        String ye = fromCp(0x041c, 0x0435, 0x0434); // "Мед"
        String yo = fromCp(0x041c, 0x0451, 0x0434); // "Мёд"
        PredefinedWriter.create(catalog, ye, new PredefinedWriter.ItemProps(), false);
        // Writer-level create with the 'yo' spelling is NOT rejected as a duplicate of the stored 'ye'
        // item (exact check) - the tool's normalization, not the writer, is what makes them collide.
        PredefinedWriter.WriteResult second =
            PredefinedWriter.create(catalog, yo, new PredefinedWriter.ItemProps(), false);
        assertFalse("the writer's duplicate check is exact - a yo-variant is not a duplicate", //$NON-NLS-1$
            second.isError());
    }

    // ==================== descendants() (issue #296 P1: delete_metadata's reference cascade) ==========

    @Test
    public void testDescendantsOfLeafIsEmpty()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.WriteResult leaf =
            PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertTrue(PredefinedWriter.descendants(leaf.item).isEmpty());
    }

    @Test
    public void testDescendantsOfFolderIncludesWholeSubtree()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps folderProps = new PredefinedWriter.ItemProps();
        folderProps.isFolder = true;
        folderProps.isFolderSet = true;
        PredefinedWriter.WriteResult folder = PredefinedWriter.create(catalog, "Warm", folderProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps nestedFolderProps = new PredefinedWriter.ItemProps();
        nestedFolderProps.isFolder = true;
        nestedFolderProps.isFolderSet = true;
        nestedFolderProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Reds", nestedFolderProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps leafProps = new PredefinedWriter.ItemProps();
        leafProps.parentName = "Reds"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Crimson", leafProps, false); //$NON-NLS-1$

        PredefinedWriter.ItemProps directChildProps = new PredefinedWriter.ItemProps();
        directChildProps.parentName = "Warm"; //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Orange", directChildProps, false); //$NON-NLS-1$

        // Warm -> Reds -> Crimson, and Warm -> Orange: 3 descendant OBJECTS (mirrors
        // testDeletePreviewFolderCountsAllDescendants's cascade shape, but returning the items
        // themselves - what delete_metadata's reference scan needs - not just a count).
        List<PredefinedItem> descendants = PredefinedWriter.descendants(folder.item);
        assertEquals(3, descendants.size());
        boolean hasReds = false;
        boolean hasCrimson = false;
        boolean hasOrange = false;
        for (PredefinedItem d : descendants)
        {
            hasReds |= "Reds".equals(d.getName()); //$NON-NLS-1$
            hasCrimson |= "Crimson".equals(d.getName()); //$NON-NLS-1$
            hasOrange |= "Orange".equals(d.getName()); //$NON-NLS-1$
        }
        assertTrue("every nested descendant must be present", hasReds && hasCrimson && hasOrange); //$NON-NLS-1$
    }

    // ==================== valueType (issue #296 P2: CCT predefined items) ==============================
    //
    // The SUCCESSFUL build (a real TypeDescription resolved via the platform type provider) needs a
    // live EDT platform and is E2E-covered (mirrors MetadataTypeBuilderTest's own documented split:
    // "the build() happy path needs the platform type provider"). What IS unit-testable here: the
    // owner-type gate (CCT only), the create-time-null / missing-context guards (all pure, before
    // MetadataTypeBuilder is ever touched), the null-CLEARS-on-modify path (also pure - it short-circuits
    // before touching Configuration/Version), and the render path (formatValueType/displayValueType)
    // once a TypeDescription IS set (built directly here with McoreFactory, bypassing the platform
    // resolver entirely - proving the getType()/setType() plumbing and the renderer, independent of how
    // the TypeDescription was produced).

    @Test
    public void testParsePropertiesAcceptsValueTypeName()
    {
        PredefinedWriter.ItemProps out = parse(List.of(prop("valueType", typeSpec("String"))), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(out.valueTypeSet);
        assertNotNull(out.valueType);
    }

    @Test
    public void testParsePropertiesAcceptsTypeAlias()
    {
        PredefinedWriter.ItemProps out = parse(List.of(prop("type", typeSpec("String"))), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the 'type' alias must be accepted the same as 'valueType'", out.valueTypeSet); //$NON-NLS-1$
    }

    @Test
    public void testParsePropertiesValueTypeWithoutValueRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(prop("valueType", null)), false, out); //$NON-NLS-1$
        assertNotNull("a 'valueType' entry with no 'value' must be rejected", err); //$NON-NLS-1$
        assertTrue(err.contains("needs a 'value'")); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeRejectedForCatalogOwnerOnCreate()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.valueType = typeSpec("String"); //$NON-NLS-1$
        props.valueTypeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue("a Catalog item must reject valueType with an actionable error", //$NON-NLS-1$
            result.error.contains("ChartOfCharacteristicTypes")); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeRejectedForCatalogOwnerOnModify()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.valueType = typeSpec("String"); //$NON-NLS-1$
        mod.valueTypeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "Blue", mod); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("ChartOfCharacteristicTypes")); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeNullAtCreateRejected()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.valueType = JsonNull.INSTANCE;
        props.valueTypeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Weight", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("cannot be JSON null at create")); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeMissingContextRejected()
    {
        // config/version deliberately left unset - exactly what every EXISTING caller of create()/
        // modify() (none of which touch valueType) leaves them at; PredefinedWriter must still fail
        // ACTIONABLY (not NPE) when a caller DOES set valueTypeSet without supplying the context.
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.valueType = typeSpec("String"); //$NON-NLS-1$
        props.valueTypeSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Weight", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue("a missing platform-version context must fail actionably, not NPE", //$NON-NLS-1$
            result.error.contains("platform version")); //$NON-NLS-1$
    }

    @Test
    public void testValueTypeModifyNullClearsExistingType()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        PredefinedWriter.WriteResult created =
            PredefinedWriter.create(types, "Weight", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        ChartOfCharacteristicTypesPredefinedItem item =
            (ChartOfCharacteristicTypesPredefinedItem)created.item;
        // Set directly (bypassing the platform-resolving writer path) - this test targets the CLEAR,
        // which never touches MetadataTypeBuilder either way.
        item.setType(McoreFactory.eINSTANCE.createTypeDescription());
        assertNotNull("precondition: a value type is set before the clear", item.getType()); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.valueType = JsonNull.INSTANCE;
        mod.valueTypeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(types, "Weight", mod); //$NON-NLS-1$
        assertFalse(result.isError());
        assertNull("an explicit JSON null must clear the value type", item.getType()); //$NON-NLS-1$
    }

    @Test
    public void testFormatValueTypeNullForNullOrEmptyDescription()
    {
        assertNull(PredefinedWriter.formatValueType(null));
        TypeDescription empty = McoreFactory.eINSTANCE.createTypeDescription();
        assertNull("no types in the list -> no rendered text", PredefinedWriter.formatValueType(empty)); //$NON-NLS-1$
    }

    @Test
    public void testDisplayValueTypeNullForCatalogItem()
    {
        // A Catalog item has NO value-type concept at all - dash-worthy null, not an error.
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.WriteResult result =
            PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertNull(PredefinedWriter.displayValueType(result.item));
    }

    @Test
    public void testDisplayValueTypeRendersOnceSet()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        PredefinedWriter.WriteResult result =
            PredefinedWriter.create(types, "Weight", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        ChartOfCharacteristicTypesPredefinedItem item = (ChartOfCharacteristicTypesPredefinedItem)result.item;
        assertNull("no value type until set", PredefinedWriter.displayValueType(item)); //$NON-NLS-1$

        // A BARE (unstubbed) mock proves NOTHING here: formatValueType's fallback chain is
        // McoreUtil.getTypeName -> McoreUtil.getTypeNameRu -> typeItem.getClass().getSimpleName(), and
        // an unstubbed mock makes the first two return null - so the assertion would pass via the
        // LAST-RESORT fallback (a Mockito-generated class name like "TypeItem$MockitoMock$..." is
        // always non-null) regardless of whether the real renderer chain ever ran.
        //
        // A REAL, platform-RESOLVED TypeItem (the kind MetadataTypeBuilder builds via
        // provider.createProxy(name)) needs the live platform type provider, not available in a bare
        // unit test (see the class doc above) - so instead this stubs the mock's OWN dual-name
        // accessors (getName()/getNameRu(), inherited from DuallyNamedElement/NamedElement - a REAL
        // part of the TypeItem contract, not an artifact of mocking) with a sentinel that could only
        // reach the output if McoreUtil.getTypeName/getTypeNameRu actually read it off the item. This
        // makes the assertion depend on the renderer chain actually being invoked, not on the
        // class-name fallback: the fallback branch would render the mock's proxy class name, which
        // never contains the sentinel.
        String sentinel = "ZZZ_SENTINEL_TYPE_NAME_296"; //$NON-NLS-1$
        TypeItem stubbedTypeItem = Mockito.mock(TypeItem.class);
        Mockito.when(stubbedTypeItem.getName()).thenReturn(sentinel);
        Mockito.when(stubbedTypeItem.getNameRu()).thenReturn(sentinel);
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        td.getTypes().add(stubbedTypeItem);
        item.setType(td);

        assertNotNull("getType() is now set", item.getType()); //$NON-NLS-1$
        String rendered = PredefinedWriter.displayValueType(item);
        assertNotNull("a set value type must render as non-null text", rendered); //$NON-NLS-1$
        assertTrue("rendered text must CONTAIN the stubbed type name, proving the renderer chain " //$NON-NLS-1$
            + "actually ran (a bare unstubbed mock would satisfy a mere non-null check via its " //$NON-NLS-1$
            + "class-name fallback, proving nothing) - got: " + rendered, //$NON-NLS-1$
            rendered.contains(sentinel));
    }

    // ==================== descriptionLength (issue #296 addendum) ======================================

    @Test
    public void testDescriptionExactlyAtLimitAccepted()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        catalog.setDescriptionLength(5);
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.description = "Hello"; // 5 chars, exactly at the limit //$NON-NLS-1$
        props.descriptionSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertFalse("exactly-at-limit must be accepted, not rejected", result.isError()); //$NON-NLS-1$
        assertEquals("Hello", result.item.getDescription()); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionOverLimitRejectedOnCreate()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        catalog.setDescriptionLength(4);
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.description = "Hello"; // 5 chars > 4 //$NON-NLS-1$
        props.descriptionSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue("an over-long description must be REJECTED, never silently truncated", //$NON-NLS-1$
            result.isError());
        assertTrue(result.error.contains("descriptionLength")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionOmittedDefaultsToNameAlsoValidated()
    {
        // An OMITTED description defaults to the item's Name (existing behaviour) - the length check
        // must cover that default too, not just an explicitly supplied description.
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        catalog.setDescriptionLength(3);

        PredefinedWriter.WriteResult result =
            PredefinedWriter.create(catalog, "VeryLongName", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("descriptionLength")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionUnlimitedWhenLengthZeroAccepted()
    {
        // descriptionLength defaults to 0 on a bare MdClassFactory fixture - the SAME "0 = unlimited"
        // convention codeLength already uses.
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.description = "A description far longer than any typical designer-authored limit, just " //$NON-NLS-1$
            + "to be sure zero really means unlimited and nothing rejects it."; //$NON-NLS-1$
        props.descriptionSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
    }

    @Test
    public void testDescriptionOverLimitRejectedOnModify()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        catalog.setDescriptionLength(4);
        PredefinedWriter.create(catalog, "Blue", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        PredefinedWriter.ItemProps mod = new PredefinedWriter.ItemProps();
        mod.description = "TooLong"; //$NON-NLS-1$
        mod.descriptionSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.modify(catalog, "Blue", mod); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("descriptionLength")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionOverLimitRejectedForCharacteristicTypesOwner()
    {
        ChartOfCharacteristicTypes types = newCharacteristicTypes("Properties", 5); //$NON-NLS-1$
        types.setDescriptionLength(4);
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.description = "Hello"; // 5 chars > 4 //$NON-NLS-1$
        props.descriptionSet = true;

        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Weight", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("descriptionLength")); //$NON-NLS-1$
    }

    // ==================== wire-robust clear (code / valueType) ====================
    // The MCP wire drops a null-valued key on the way in, so an explicit `null` reaches the tool as a
    // MISSING value. On MODIFY, a missing/null code or valueType therefore CLEARS it; on CREATE it is
    // still malformed (nothing to clear).

    /** modify: a `code` entry with a MISSING value clears the code (wire-stripped null). */
    @Test
    public void testModifyCodeMissingValueClearsIt()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(java.util.List.of(prop("code", null)), true, out); //$NON-NLS-1$
        assertNull("a missing code value on modify must clear, not error: " + err, err); //$NON-NLS-1$
        assertTrue(out.codeSet);
        assertTrue("the cleared code is carried as JSON null", out.code != null && out.code.isJsonNull()); //$NON-NLS-1$
    }

    /** create: a `code` entry with a MISSING value is still rejected (nothing to clear). */
    @Test
    public void testCreateCodeMissingValueRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(java.util.List.of(prop("code", null)), false, out); //$NON-NLS-1$
        assertNotNull("a missing code value on create must be rejected", err); //$NON-NLS-1$
        assertTrue(err.contains("needs a 'value'")); //$NON-NLS-1$
    }

    /** modify: a `valueType` entry with a MISSING value clears it (wire-stripped null). */
    @Test
    public void testModifyValueTypeMissingValueClearsIt()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(java.util.List.of(prop("valueType", null)), true, out); //$NON-NLS-1$
        assertNull("a missing valueType value on modify must clear, not error: " + err, err); //$NON-NLS-1$
        assertTrue(out.valueTypeSet);
        assertTrue("the cleared value type is carried as JSON null", //$NON-NLS-1$
            out.valueType != null && out.valueType.isJsonNull());
    }

    /** create: a `valueType` entry with a MISSING value is still rejected. */
    @Test
    public void testCreateValueTypeMissingValueRejected()
    {
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(java.util.List.of(prop("valueType", null)), false, out); //$NON-NLS-1$
        assertNotNull("a missing valueType value on create must be rejected", err); //$NON-NLS-1$
        assertTrue(err.contains("needs a 'value'")); //$NON-NLS-1$
    }
}