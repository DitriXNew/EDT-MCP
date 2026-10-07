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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport.Plan;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport.Request;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport.RootKind;
import com.ditrix.edt.mcp.server.utils.UseAlwaysSupport.UseAlwaysDefault;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * The "Use always" checkbox of a form attribute's paths (#661): the wire grammar, the platform's
 * root-type inversion of {@code notDefaultUseAlwaysAttributes}, and the refusals. Headless, so the
 * platform's data tree is unavailable and every path takes the replicated rule.
 */
public class UseAlwaysSupportTest
{
    /** ИспользоватьОтчет - a Russian programmatic constant Name. */
    private static final String RU_CONSTANT =
        "\u0418\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u044c\u041e\u0442\u0447\u0435\u0442"; //$NON-NLS-1$

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
    public void testTheListMeaningFlipsWithTheDefault()
    {
        // A CHECKED default lists the paths that are NOT used always; an UNCHECKED one lists the
        // paths that ARE - EDT UseAlwaysAttributeService.setUseAlwaysValue.
        assertTrue(UseAlwaysSupport.listed(UseAlwaysDefault.CHECKED, false));
        assertFalse(UseAlwaysSupport.listed(UseAlwaysDefault.CHECKED, true));
        assertTrue(UseAlwaysSupport.listed(UseAlwaysDefault.UNCHECKED, true));
        assertFalse(UseAlwaysSupport.listed(UseAlwaysDefault.UNCHECKED, false));
    }

    @Test
    public void testEachRootKindHasThePlatformDefault()
    {
        assertEquals(RootKind.CONSTANTS_SET, UseAlwaysSupport.rootKind(attribute("Set", "ConstantsSet"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RootKind.RECORDS_COLLECTION,
            UseAlwaysSupport.rootKind(attribute("Records", "RegisterRecordsCollection"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RootKind.DYNAMIC_LIST, UseAlwaysSupport.rootKind(attribute("List", "DynamicList"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RootKind.GANTT_CHART, UseAlwaysSupport.rootKind(attribute("Chart", "GanttChart"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RootKind.OTHER, UseAlwaysSupport.rootKind(attribute("Object", "CatalogObject.Products"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(RootKind.OTHER, UseAlwaysSupport.rootKind(attribute("Table", "ValueTable"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a ConstantsSet in a compound type is not a constants-set root", RootKind.OTHER, //$NON-NLS-1$
            UseAlwaysSupport.rootKind(attribute("Mixed", "ConstantsSet", "String"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a dynamic-list ext-info marks a list even without the type", RootKind.DYNAMIC_LIST, //$NON-NLS-1$
            UseAlwaysSupport.rootKind(dynamicListByExtInfo()));

        assertEquals(UseAlwaysDefault.UNCHECKED, UseAlwaysSupport.rootDefault(RootKind.CONSTANTS_SET));
        assertEquals(UseAlwaysDefault.UNCHECKED, UseAlwaysSupport.rootDefault(RootKind.RECORDS_COLLECTION));
        assertEquals(UseAlwaysDefault.UNCHECKED, UseAlwaysSupport.rootDefault(RootKind.DYNAMIC_LIST));
        assertEquals(UseAlwaysDefault.NONE, UseAlwaysSupport.rootDefault(RootKind.GANTT_CHART));
        assertEquals(UseAlwaysDefault.CHECKED, UseAlwaysSupport.rootDefault(RootKind.OTHER));
    }

    @Test
    public void testAConstantsSetListsTheConstantsUsedAlways()
    {
        Configuration config = configWithConstants("UseReport", RU_CONSTANT); //$NON-NLS-1$
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute set = attribute("ConstantsSet", "ConstantsSet"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getAttributes().add(set);

        write(config, form, set, "{\"ConstantsSet.UseReport\": true, \"ConstantsSet." + RU_CONSTANT + "\": true}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("ConstantsSet.UseReport", "ConstantsSet." + RU_CONSTANT), stored(set)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"ConstantsSet.UseReport\":true,\"ConstantsSet." + RU_CONSTANT + "\":true}", //$NON-NLS-1$ //$NON-NLS-2$
            UseAlwaysSupport.render(set)[0]);

        write(config, form, set, "{\"ConstantsSet.useReport\": false}"); //$NON-NLS-1$
        assertEquals("unchecking removes the path whatever its casing", //$NON-NLS-1$
            Arrays.asList("ConstantsSet." + RU_CONSTANT), stored(set)); //$NON-NLS-1$
        write(config, form, set, "{\"ConstantsSet." + RU_CONSTANT + "\": true}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a listed path is not listed twice", 1, stored(set).size()); //$NON-NLS-1$
    }

    @Test
    public void testAnOrdinaryObjectListsTheFieldsNotUsedAlways()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute object = attribute("Object", "CatalogObject.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getAttributes().add(object);

        write(null, form, object, "{\"Object.Code\": false, \"Object.Description\": true}"); //$NON-NLS-1$
        assertEquals("only the unchecked field is listed", Arrays.asList("Object.Code"), stored(object)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"Object.Code\":false}", UseAlwaysSupport.render(object)[0]); //$NON-NLS-1$

        write(null, form, object, "{\"Object.Code\": true}"); //$NON-NLS-1$
        assertTrue("checking it again restores the default", stored(object).isEmpty()); //$NON-NLS-1$
        assertNull(UseAlwaysSupport.render(object));
    }

    @Test
    public void testAnUnknownConstantIsRefusedBeforeAnythingIsWritten()
    {
        Configuration config = configWithConstants("UseReport"); //$NON-NLS-1$
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute set = attribute("ConstantsSet", "ConstantsSet"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getAttributes().add(set);

        String err = planError(config, form, set, "{\"ConstantsSet.Missing\": true}"); //$NON-NLS-1$
        assertNotNull(err);
        assertTrue(err, err.contains("'Missing' is not a constant of the configuration")); //$NON-NLS-1$
        assertTrue(err, err.contains("metadataType 'Constant'")); //$NON-NLS-1$
        err = planError(config, form, set, "{\"ConstantsSet.UseReport.Ref\": true}"); //$NON-NLS-1$
        assertTrue(err, err.contains("goes below a constant")); //$NON-NLS-1$
        assertTrue(stored(set).isEmpty());
    }

    @Test
    public void testAGanttChartHasNoCheckbox()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute chart = attribute("Chart", "GanttChart"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getAttributes().add(chart);

        String err = planError(null, form, chart, "{\"Chart.Points\": true}"); //$NON-NLS-1$
        assertTrue(err, err.contains("'Chart.Points' has no 'Use always' checkbox")); //$NON-NLS-1$
    }

    @Test
    public void testADynamicListFieldShownByAnItemCannotBeUnchecked()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute list = attribute("List", "DynamicList"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getAttributes().add(list);
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("ListRef"); //$NON-NLS-1$
        DataPath bound = FormFactory.eINSTANCE.createDataPath();
        bound.getSegments().addAll(Arrays.asList("List", "Ref")); //$NON-NLS-1$ //$NON-NLS-2$
        field.setDataPath(bound);
        form.getItems().add(field);

        String err = planError(null, form, list, "{\"List.ref\": false}"); //$NON-NLS-1$
        assertTrue(err, err.contains("the form item 'ListRef' shows that dynamic-list field")); //$NON-NLS-1$

        write(null, form, list, "{\"List.Ref\": true, \"List.Date\": false}"); //$NON-NLS-1$
        assertEquals("a dynamic list lists the columns used always", Arrays.asList("List.Ref"), stored(list)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void write(Configuration config, Form form, FormAttribute attribute, String payload)
    {
        List<Request> requests = new ArrayList<>();
        assertNull(UseAlwaysSupport.parse(attribute.getName(), json(payload), requests));
        List<Plan> plans = new ArrayList<>();
        String err = UseAlwaysSupport.plan(config, form, attribute, requests, plans);
        assertNull(err, err);
        UseAlwaysSupport.apply(form, attribute, plans);
    }

    private static String planError(Configuration config, Form form, FormAttribute attribute, String payload)
    {
        List<Request> requests = new ArrayList<>();
        assertNull(UseAlwaysSupport.parse(attribute.getName(), json(payload), requests));
        return UseAlwaysSupport.plan(config, form, attribute, requests, new ArrayList<>());
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

    private static FormAttribute attribute(String name, String... typeNames)
    {
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName(name);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        for (String typeName : typeNames)
        {
            Type type = McoreFactory.eINSTANCE.createType();
            type.setName(typeName);
            description.getTypes().add(type);
        }
        attribute.setValueType(description);
        return attribute;
    }

    private static FormAttribute dynamicListByExtInfo()
    {
        FormAttribute attribute = attribute("List"); //$NON-NLS-1$
        attribute.setExtInfo(FormFactory.eINSTANCE.createDynamicListExtInfo());
        return attribute;
    }

    private static Configuration configWithConstants(String... names)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : names)
        {
            Constant constant = MdClassFactory.eINSTANCE.createConstant();
            constant.setName(name);
            config.getConstants().add(constant);
        }
        return config;
    }

    private static JsonElement json(String text)
    {
        return JsonParser.parseString(text);
    }
}
