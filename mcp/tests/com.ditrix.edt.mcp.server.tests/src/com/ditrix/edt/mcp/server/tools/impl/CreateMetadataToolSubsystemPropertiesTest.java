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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Issue #930: {@code create_metadata} for a SUBSYSTEM applies the properties {@code modify_metadata} sets on
 * one ({@code includeInCommandInterface}, {@code includeHelpInContents}, {@code useOneCommand}, ...) at
 * creation, validated and written by {@code modify_metadata}'s own rules - one vocabulary, not a second
 * list. Every other kind keeps refusing such a property by name. A {@code properties} argument that is
 * not an array of objects is refused in both tools instead of being read as "no properties" and dropped.
 *
 * <p>The new seams are reached by reflection, so the suite compiles against the code as it was before
 * the fix and every case has a failing baseline.</p>
 */
public class CreateMetadataToolSubsystemPropertiesTest
{
    private static final Class<?>[] PARSE_TYPES = {List.class, CreateMetadataTool.Props.class,
        MdNameNormalizer.Report.class, boolean.class};

    // ---- parse: which names reach the writer -------------------------------------------------------

    @Test
    public void testASubsystemCreateKeepsAModifyPropertyForTheWriter()
    {
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        Object error = invoke(new CreateMetadataTool(), "parseProperties", PARSE_TYPES,
            list("{name:'includeInCommandInterface', value:false}", "{name:'synonym', value:'Orders'}"),
            props, new MdNameNormalizer.Report(true), true);
        assertNull("a subsystem takes modify_metadata's properties at creation: " + error, error);
        assertEquals("Orders", props.synonym);
        List<?> writer = (List<?>)field(props, "writerProperties");
        assertEquals("only the property the create path does not apply itself goes to the writer", 1,
            writer.size());
    }

    @Test
    public void testAnyOtherKindStillRefusesThePropertyByName()
    {
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        String error = (String)invoke(new CreateMetadataTool(), "parseProperties", PARSE_TYPES,
            list("{name:'includeInCommandInterface', value:false}"), props, new MdNameNormalizer.Report(true), false);
        assertNotNull("never dropped silently", error);
        assertTrue(error, error.contains("'includeInCommandInterface' is not supported yet in create_metadata"));
        assertTrue(error, error.contains("modify_metadata"));
    }

    // ---- prepare: modify_metadata's own rules ------------------------------------------------------

    @Test
    public void testThePropertyIsPreparedAndAppliedByModifysWriter()
    {
        CreateMetadataTool.Props props = writerProps("{name:'includeInCommandInterface', value:false}",
            "{name:'includeHelpInContents', value:'false'}");
        assertNull(prepare(props));
        Subsystem created = MdClassFactory.eINSTANCE.createSubsystem();
        created.setIncludeInCommandInterface(true);
        created.setIncludeHelpInContents(true);
        ModifyMetadataTool.applyChanges(created, writerChanges(props), null);
        assertFalse("the value the call asked for lands on the new subsystem", created.isIncludeInCommandInterface());
        assertFalse(created.isIncludeHelpInContents());
    }

    @Test
    public void testAPropertyModifyDoesNotKnowIsRefusedWithItsAssignableList()
    {
        String error = prepare(writerProps("{name:'noSuchProperty', value:true}"));
        assertNotNull(error);
        assertTrue(error, error.contains("'noSuchProperty' is not assignable on Subsystem"));
        assertTrue("the refusal names what IS settable: " + error, error.contains("includeInCommandInterface"));
    }

    @Test
    public void testABadValueIsRefusedByModifysRule()
    {
        String error = prepare(writerProps("{name:'includeInCommandInterface', value:'maybe'}"));
        assertNotNull(error);
        assertTrue(error, error.contains("includeInCommandInterface"));
    }

    @Test
    public void testWhatTheCreateItselfDecidesIsRefused()
    {
        for (String name : new String[] {"name", "uuid", "objectBelonging", "parentSubsystem", "subsystems",
            "extendedConfigurationObject", "Name"})
        {
            String error = prepare(writerProps("{name:'" + name + "', value:'X'}"));
            assertNotNull(name, error);
            assertTrue(name + ": " + error, error.contains("'" + name + "'"));
            assertTrue(name + ": " + error, error.contains("create_metadata decides"));
            assertTrue(name + ": " + error, error.contains("Nothing was created"));
        }
    }

    @Test
    public void testNoWriterPropertyPreparesNothing()
    {
        CreateMetadataTool.Props props = writerProps();
        assertNull(prepare(props));
        assertTrue(writerChanges(props).isEmpty());
    }

    // ---- the shape of 'properties' -----------------------------------------------------------------

    @Test
    public void testAnObjectInsteadOfAnArrayIsRefusedNotDropped()
    {
        String error = createShapeRefusal("{\"includeInCommandInterface\":false}");
        assertNotNull("an object is not 'no properties'", error);
        assertTrue(error, error.contains("'properties' must be an array of {name, value} objects"));
        assertTrue(error, error.contains("a JSON object"));
        assertTrue(error, error.contains("Nothing was created"));
    }

    @Test
    public void testANonObjectItemIsRefusedNotSkipped()
    {
        String error = createShapeRefusal("[{\"name\":\"comment\",\"value\":\"x\"}, \"includeInCommandInterface\"]");
        assertNotNull(error);
        assertTrue(error, error.contains("item 2"));
    }

    @Test
    public void testAWellFormedOrAbsentArgumentPasses()
    {
        assertNull(createShapeRefusal("[{\"name\":\"comment\",\"value\":\"x\"}]"));
        assertNull(createShapeRefusal("[]"));
        assertNull(createShapeRefusal(null));
        assertNull(createShapeRefusal("null"));
        assertNull(createShapeRefusal("  "));
    }

    @Test
    public void testTheSharedRuleNamesTheArgumentAndTheShape()
    {
        Map<String, String> params = new HashMap<>();
        params.put("properties", "true");
        String error = (String)invokeStatic(JsonUtils.class, "objectArrayShapeError",
            new Class<?>[] {Map.class, String.class}, params, "properties");
        assertNotNull(error);
        assertTrue(error, error.contains("'properties' must be an array"));
        assertTrue(error, error.contains("a single value"));
        params.put("properties", "not json at all {");
        error = (String)invokeStatic(JsonUtils.class, "objectArrayShapeError",
            new Class<?>[] {Map.class, String.class}, params, "properties");
        assertNotNull(error);
    }

    @Test
    public void testModifyRefusesAnObjectInsteadOfSayingPropertiesIsRequired()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "P");
        params.put("fqn", "Subsystem.Sales.Subsystem.Orders");
        params.put("properties", "{\"includeInCommandInterface\":false}");
        String error = modifyArgsError(params);
        assertNotNull(error);
        assertTrue(error, error.contains("'properties' must be an array of {name, value} objects"));
        assertFalse(error, error.contains("properties is required"));
        assertTrue(error, error.contains("Nothing was changed"));
    }

    @Test
    public void testModifyDoesNotDropAMalformedPropertiesBesideAContentPayload()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "P");
        params.put("fqn", "Subsystem.Sales");
        params.put("content", "[{\"op\":\"add\",\"metadata\":\"Catalog.Goods\"}]");
        params.put("properties", "{\"includeInCommandInterface\":false}");
        String error = modifyArgsError(params);
        assertNotNull("the content would be written and the properties silently lost", error);
        assertTrue(error, error.contains("must be an array"));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static String prepare(CreateMetadataTool.Props props)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        return (String)invokeStatic(CreateMetadataTool.class, "prepareWriterChanges",
            new Class<?>[] {IProject.class, MetadataScope.class, Configuration.class, Version.class, EClass.class,
                CreateMetadataTool.Props.class, MdNameNormalizer.Report.class, boolean.class},
            null, MetadataScope.ofConfiguration(config), config, null, MdClassPackage.Literals.SUBSYSTEM, props,
            new MdNameNormalizer.Report(true), false);
    }

    @SuppressWarnings("unchecked")
    private static List<ModifyMetadataTool.PreparedChange> writerChanges(CreateMetadataTool.Props props)
    {
        return (List<ModifyMetadataTool.PreparedChange>)field(props, "writerChanges");
    }

    @SuppressWarnings("unchecked")
    private static CreateMetadataTool.Props writerProps(String... properties)
    {
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        ((List<JsonObject>)field(props, "writerProperties")).addAll(list(properties));
        return props;
    }

    private static String createShapeRefusal(String properties)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "P");
        params.put("fqn", "Subsystem.Sales");
        if (properties != null)
        {
            params.put("properties", properties);
        }
        return (String)invokeStatic(CreateMetadataTool.class, "propertiesShapeRefusal",
            new Class<?>[] {Map.class}, params);
    }

    private static String modifyArgsError(Map<String, String> params)
    {
        Object args = invokeStatic(ModifyMetadataTool.class, "parseModifyArgs", new Class<?>[] {Map.class}, params);
        return (String)field(args, "error");
    }

    private static List<JsonObject> list(String... objects)
    {
        List<JsonObject> out = new ArrayList<>();
        JsonArray array = JsonParser.parseString("[" + String.join(",", objects) + "]").getAsJsonArray();
        array.forEach(element -> out.add(element.getAsJsonObject()));
        return out;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments)
    {
        try
        {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The seam must exist and be callable: " + name, e);
        }
    }

    private static Object invokeStatic(Class<?> owner, String name, Class<?>[] types, Object... arguments)
    {
        try
        {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The seam must exist and be callable: " + owner.getSimpleName() + "." + name, e);
        }
    }

    private static Object field(Object owner, String name)
    {
        try
        {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("The holder must expose " + name, e);
        }
    }
}
