/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.MultiLanguageDataPath;
import com._1c.g5.v8.dt.form.service.attribute.IUseAlwaysAttributeService.UseAlways;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport.Plan;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport.Request;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The "Use always" checkbox of a form attribute's paths (#661): the wire grammar, and the route
 * through EDT's form services - resolution, the checkbox default, the setter and the read-back -
 * driven through {@link UseAlwaysPlatformFake}, whose defaults are registered per path.
 */
public class UseAlwaysSupportTest
{
    /** ИспользоватьОтчет - a Russian programmatic constant Name. */
    private static final String RU_CONSTANT = "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c\u041e\u0442\u0447\u0435\u0442"; //$NON-NLS-1$
    /** Код. */
    private static final String RU_CODE = "\u041a\u043e\u0434"; //$NON-NLS-1$
    /** Наименование. */
    private static final String RU_DESCRIPTION = "\u041d\u0430\u0438\u043c\u0435\u043d\u043e\u0432\u0430\u043d\u0438\u0435"; //$NON-NLS-1$

    private UseAlwaysPlatformFake platform;

    @Before
    public void installThePlatform()
    {
        platform = new UseAlwaysPlatformFake();
        platform.install();
    }

    @After
    public void restoreThePlatform()
    {
        UseAlwaysPlatformFake.uninstall();
    }

    @Test
    public void testThePathsParseWithTheAttributeNameCanonicalized()
    {
        List<Request> requests = new ArrayList<>();
        assertNull(UseAlwaysSupport.parse("Object", //$NON-NLS-1$
            json("{\"object.Code\": false, \"Object.Goods.Product\": \"true\"}"), requests)); //$NON-NLS-1$
        assertEquals(2, requests.size());
        assertEquals(Arrays.asList("Object", "Code"), requests.get(0).segments); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(requests.get(0).useAlways);
        assertEquals(Arrays.asList("Object", "Goods", "Product"), requests.get(1).segments); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(requests.get(1).useAlways);
    }

    @Test
    public void testMalformedPayloadsAreRefusedNamingTheValue()
    {
        assertRefused("[\"Object.Code\"]", "non-empty object"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("{}", "non-empty object"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("{\"Code\": true}", "path 'Code' must be 'Object.<field>'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("{\"Object.\": true}", "path 'Object.' must be"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("{\"List.Code\": true}", "starts with 'List', not with the addressed attribute 'Object'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("{\"Object.Code\": \"maybe\"}", "must be true (use always) or false, got \"maybe\""); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("{\"Object.Code\": true, \"object.code\": false}", //$NON-NLS-1$
            "names the path 'Object.Code' twice (also as 'object.code')"); //$NON-NLS-1$
    }

    @Test
    public void testACheckedDefaultListsTheUncheckedPathThroughThePlatformSetter()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("Object.Code", "Object." + RU_CODE, UseAlways.Checked); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("Object.Description", "Object." + RU_DESCRIPTION, UseAlways.Checked); //$NON-NLS-1$ //$NON-NLS-2$

        write(form, object, "{\"Object.Code\": false, \"Object.description\": true}"); //$NON-NLS-1$
        assertEquals("every path goes through EDT's own setter", //$NON-NLS-1$
            Arrays.asList("Object.Code=false", "Object.Description=true"), platform.setCalls()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only the unchecked field is listed", Arrays.asList("Object.Code"), stored(object)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"Object.Code\":false}", UseAlwaysSupport.render(object)[0]); //$NON-NLS-1$

        // The Russian spelling of the same field resolves to it and unchecks the English entry.
        write(form, object, "{\"Object." + RU_CODE + "\": true}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("checking it again restores the default", stored(object).isEmpty()); //$NON-NLS-1$
        assertNull(UseAlwaysSupport.render(object));
    }

    @Test
    public void testAnUncheckedDefaultListsTheCheckedPath()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute set = attribute(form, "ConstantsSet", "ConstantsSet"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("ConstantsSet.UseReport", null, UseAlways.Unchecked); //$NON-NLS-1$
        platform.field("ConstantsSet." + RU_CONSTANT, null, UseAlways.Unchecked); //$NON-NLS-1$

        write(form, set, "{\"ConstantsSet.UseReport\": true, \"ConstantsSet." + RU_CONSTANT + "\": true}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("ConstantsSet.UseReport", "ConstantsSet." + RU_CONSTANT), stored(set)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"ConstantsSet.UseReport\":true,\"ConstantsSet." + RU_CONSTANT + "\":true}", //$NON-NLS-1$ //$NON-NLS-2$
            UseAlwaysSupport.render(set)[0]);

        write(form, set, "{\"ConstantsSet.useReport\": false}"); //$NON-NLS-1$
        assertEquals(Arrays.asList("ConstantsSet." + RU_CONSTANT), stored(set)); //$NON-NLS-1$
    }

    @Test
    public void testAPathThePlatformDoesNotResolveIsRefusedNamingTheSegment()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("Object", null, UseAlways.None); //$NON-NLS-1$
        platform.field("Object.Owner", null, UseAlways.Checked); //$NON-NLS-1$
        platform.field("Object.Owner.Code", null, UseAlways.None); //$NON-NLS-1$

        String err = planError(form, object, "{\"Object.Owner.Nonexistent\": true}"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue(err, err.contains("'Nonexistent' is not a field of 'Object.Owner'")); //$NON-NLS-1$
        assertTrue(err, err.contains("Fields there: Code.")); //$NON-NLS-1$
        err = planError(form, object, "{\"Object.Ghost\": false}"); //$NON-NLS-1$
        assertTrue(err, err.contains("'Ghost' is not a field of 'Object'")); //$NON-NLS-1$
        assertTrue(err, err.contains("Fields there: Owner.")); //$NON-NLS-1$
        assertTrue("nothing reaches the setter", platform.setCalls().isEmpty()); //$NON-NLS-1$
        assertTrue(stored(object).isEmpty());
    }

    @Test
    public void testAPathThePlatformGivesNoCheckboxIsRefused()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("Object.Owner", null, UseAlways.Checked); //$NON-NLS-1$
        // Through a reference: EDT gives the field no checkbox.
        platform.field("Object.Owner.Code", null, UseAlways.None); //$NON-NLS-1$

        String err = planError(form, object, "{\"Object.Owner\": false, \"Object.Owner.Code\": true}"); //$NON-NLS-1$
        assertTrue(err, err.contains("'Object.Owner.Code' has no 'Use always' checkbox")); //$NON-NLS-1$
        assertTrue("a refused call writes none of its paths", platform.setCalls().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAMissingServiceIsAPlatformFailureNotARefusal()
    {
        UseAlwaysPlatformFake.installUnavailable("Service IUseAlwaysAttributeService is unavailable"); //$NON-NLS-1$
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        List<Request> requests = new ArrayList<>();
        assertNull(UseAlwaysSupport.parse("Object", json("{\"Object.Code\": false}"), requests)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            UseAlwaysSupport.plan(form, object, requests, new ArrayList<>());
            fail("without the platform nothing can be decided"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertFalse("logged at ERROR, not demoted as a caller refusal", e instanceof Refusals.Marker); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("needs EDT's form data services")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("IUseAlwaysAttributeService is unavailable")); //$NON-NLS-1$
        }
        assertTrue(stored(object).isEmpty());
    }

    @Test
    public void testADynamicListFieldShownByAnItemCannotBeUncheckedInEitherSpelling()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute list = attribute(form, "List", "DynamicList"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("List.Description", "List." + RU_DESCRIPTION, UseAlways.Unchecked); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("List.Date", null, UseAlways.Unchecked); //$NON-NLS-1$

        // Bound in Russian, asked in English.
        FormField field = boundField(form, "ListDescription", "List." + RU_DESCRIPTION); //$NON-NLS-1$ //$NON-NLS-2$
        String err = planError(form, list, "{\"List.Description\": false}"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue(err, err.contains("the form item 'ListDescription' shows that dynamic-list field")); //$NON-NLS-1$

        // Bound in English, asked in Russian.
        field.setDataPath(UseAlwaysPlatformFake.dataPath("List.Description")); //$NON-NLS-1$
        err = planError(form, list, "{\"List." + RU_DESCRIPTION + "\": false}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(err);
        assertTrue(err, err.contains("the form item 'ListDescription'")); //$NON-NLS-1$

        // A legacy multi-language binding counts in every language.
        field.setDataPath(multiLanguage(1, "List.Description", "List." + RU_DESCRIPTION)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(planError(form, list, "{\"List.Description\": false}")); //$NON-NLS-1$

        write(form, list, "{\"List.Description\": true, \"List.Date\": false}"); //$NON-NLS-1$
        assertEquals("a shown field may be checked; an unshown one unchecked", //$NON-NLS-1$
            Arrays.asList("List.Description"), stored(list)); //$NON-NLS-1$
    }

    @Test
    public void testALegacyMultiLanguageEntryIsRemovedWhicheverLanguageIsActive()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("Object.Code", "Object." + RU_CODE, UseAlways.Checked); //$NON-NLS-1$ //$NON-NLS-2$
        // The active language (1) holds a stale spelling, so EDT's setter, which compares only the
        // active segments, does not see the entry; the English spelling at index 0 is the field.
        MultiLanguageDataPath legacy = multiLanguage(1, "Object.Code", "Object.OldCode"); //$NON-NLS-1$ //$NON-NLS-2$
        object.getNotDefaultUseAlwaysAttributes().add(legacy);
        assertEquals("the read follows the active language", //$NON-NLS-1$
            Arrays.asList("Object", "OldCode"), UseAlwaysSupport.segmentsOf(legacy)); //$NON-NLS-1$ //$NON-NLS-2$

        write(form, object, "{\"Object.Code\": true}"); //$NON-NLS-1$
        assertTrue("the entry naming the field in any language is gone", //$NON-NLS-1$
            object.getNotDefaultUseAlwaysAttributes().isEmpty());
    }

    @Test
    public void testTheReadBackIsThePlatformStateAndNamesWhatItCannotRead()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        // Two defaults under one root: no root-type rule could produce both answers.
        platform.field("Object.Code", null, UseAlways.Checked); //$NON-NLS-1$
        platform.field("Object.Total", null, UseAlways.Unchecked); //$NON-NLS-1$
        platform.field("Object.Owner.Code", null, UseAlways.None); //$NON-NLS-1$
        for (String path : new String[] { "Object.Total", "Object.Code", "Object.Gone", "Object.Owner.Code" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            object.getNotDefaultUseAlwaysAttributes().add(UseAlwaysPlatformFake.dataPath(path));
        }

        String[] rendered = UseAlwaysSupport.render(object);
        assertEquals("{\"Object.Total\":true,\"Object.Code\":false,\"Object.Gone\":\"unresolved\"," //$NON-NLS-1$
            + "\"Object.Owner.Code\":\"noCheckbox\"}", rendered[0]); //$NON-NLS-1$
        assertEquals("the identity is the stored paths alone, sorted", //$NON-NLS-1$
            "Object.Code\nObject.Gone\nObject.Owner.Code\nObject.Total", rendered[1]); //$NON-NLS-1$
    }

    @Test
    public void testTheReadBackOfALegacyEntryReadsItsActiveLanguage()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute(form, "Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        platform.field("Object.Code", "Object." + RU_CODE, UseAlways.Checked); //$NON-NLS-1$ //$NON-NLS-2$
        object.getNotDefaultUseAlwaysAttributes().add(multiLanguage(1, "Object.Code", "Object." + RU_CODE)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("{\"Object." + RU_CODE + "\":false}", UseAlwaysSupport.render(object)[0]); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void write(Form form, FormAttribute attribute, String payload)
    {
        List<Request> requests = new ArrayList<>();
        assertNull(UseAlwaysSupport.parse(attribute.getName(), json(payload), requests));
        List<Plan> plans = new ArrayList<>();
        String err = UseAlwaysSupport.plan(form, attribute, requests, plans);
        assertNull(err, err);
        UseAlwaysSupport.apply(form, attribute, plans);
    }

    private static String planError(Form form, FormAttribute attribute, String payload)
    {
        List<Request> requests = new ArrayList<>();
        assertNull(UseAlwaysSupport.parse(attribute.getName(), json(payload), requests));
        return UseAlwaysSupport.plan(form, attribute, requests, new ArrayList<>());
    }

    private static void assertRefused(String payload, String expected)
    {
        String err = UseAlwaysSupport.parse("Object", json(payload), new ArrayList<>()); //$NON-NLS-1$
        assertNotNull("must be refused: " + payload, err); //$NON-NLS-1$
        assertTrue(err, err.contains(expected));
    }

    private static List<String> stored(FormAttribute attribute)
    {
        List<String> paths = new ArrayList<>();
        for (AbstractDataPath path : attribute.getNotDefaultUseAlwaysAttributes())
        {
            paths.add(String.join(".", UseAlwaysSupport.segmentsOf(path))); //$NON-NLS-1$
        }
        return paths;
    }

    private static FormAttribute attribute(Form form, String name, String typeName)
    {
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName(name);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        description.getTypes().add(type);
        attribute.setValueType(description);
        form.getAttributes().add(attribute);
        return attribute;
    }

    private static FormField boundField(Form form, String name, String path)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(name);
        field.setDataPath(UseAlwaysPlatformFake.dataPath(path));
        form.getItems().add(field);
        return field;
    }

    private static MultiLanguageDataPath multiLanguage(int active, String... spellings)
    {
        MultiLanguageDataPath path = FormFactory.eINSTANCE.createMultiLanguageDataPath();
        for (String spelling : spellings)
        {
            path.getPaths().add(UseAlwaysPlatformFake.dataPath(spelling));
        }
        path.setActiveLanguage(active);
        return path;
    }

    private static JsonElement json(String text)
    {
        return JsonParser.parseString(text);
    }
}
