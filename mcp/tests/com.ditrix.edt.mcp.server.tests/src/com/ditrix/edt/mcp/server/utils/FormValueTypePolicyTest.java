/**
 * Copyright (c) 2026 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeDescriptionInfoWithCategories;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** The picker is faked; form members and the write preparation/validator are real. */
public class FormValueTypePolicyTest
{
    private BiFunction<EObject, EReference, TypeDescriptionInfoWithCategories> previous;
    private TypeDescriptionInfoWithCategories picker;
    private static final List<String> COLLECTIONS =
        List.of("Array", "Structure", "Map", "FixedArray", "FixedStructure", "FixedMap");

    @Before
    public void setUp()
    {
        previous = FormValueTypePolicy.typeInfoSource;
        picker = mock(TypeDescriptionInfoWithCategories.class);
        when(picker.contains(any(TypeItem.class))).thenAnswer(call ->
            Set.of("String", "Number", "Boolean", "Date", "CatalogRef.Goods", "ValueTable")
                .contains(McoreUtil.getTypeName(call.getArgument(0))));
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> picker;
    }

    @After
    public void tearDown()
    {
        FormValueTypePolicy.typeInfoSource = previous;
    }

    @Test
    public void rejectsEveryCollectionAndNamesItInMixedTypes()
    {
        for (String kind : COLLECTIONS)
        {
            var problem = FormValueTypePolicy.check(parameter(), types("String", kind));
            assertNotNull(kind, problem);
            assertEquals(FormValueTypePolicy.INVALID_TYPE, problem.code());
            assertTrue(problem.message(), problem.message().contains("'" + kind + "'"));
            assertTrue(problem.message().contains("declare the parameter without a type"));
            assertTrue(problem.message().contains("<valueType/>"));
        }
    }

    @Test
    public void delegatesMembershipToPickerInsteadOfACollectionBlacklist()
    {
        assertNull(FormValueTypePolicy.check(parameter(), types("String", "CatalogRef.Goods")));
        assertNotNull(FormValueTypePolicy.check(parameter(), types("UnlistedPlatformType")));
        when(picker.contains(any(TypeItem.class))).thenReturn(true);
        assertNull(FormValueTypePolicy.check(parameter(), types("Array")));
    }

    @Test
    public void queriesTheActualOwnerAndFeatureIncludingAttributesAndColumns()
    {
        FormAttribute attr = FormFactory.eINSTANCE.createFormAttribute();
        attr.setName("Rows");
        assertNull(FormValueTypePolicy.check(attr, types("ValueTable")));
        assertNotNull(FormValueTypePolicy.check(attr, types("Structure")));
        var column = FormFactory.eINSTANCE.createFormAttributeColumn();
        column.setName("Cell");
        assertNotNull(FormValueTypePolicy.check(column, types("Map")));
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> {
            assertSame(attr, owner);
            assertSame(attr.eClass().getEStructuralFeature("valueType"), feature);
            return picker;
        };
        assertNull(FormValueTypePolicy.check(attr, types("String")));
    }

    @Test
    public void arbitraryTypeNeedsNoPicker()
    {
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> {
            throw new AssertionError("an untyped member needs no type picker");
        };
        assertNull(FormValueTypePolicy.check(parameter(), types()));
        assertNull(FormValueTypePolicy.check(parameter(), null));
    }

    @Test
    public void unavailablePickerAndUnreadableTypeFailClosed()
    {
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> null;
        assertEquals(FormValueTypePolicy.UNAVAILABLE,
            FormValueTypePolicy.check(parameter(), types("String")).code());
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> {
            throw new IllegalStateException("not ready");
        };
        assertEquals(FormValueTypePolicy.UNAVAILABLE,
            FormValueTypePolicy.check(parameter(), types("String")).code());
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> picker;
        assertEquals(FormValueTypePolicy.UNAVAILABLE,
            FormValueTypePolicy.check(parameter(), types((String)null)).code());
    }

    @Test
    public void validatorReportsPersistedParameterAndAttributeAndThenClearsThem()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormParameter param = parameter();
        param.setValueType(types("Array"));
        form.getParameters().add(param);
        FormAttribute attr = FormFactory.eINSTANCE.createFormAttribute();
        attr.setName("Payload");
        attr.setValueType(types("FixedStructure"));
        form.getAttributes().add(attr);
        var invalid = FormModelValidator.validate(form).stream()
            .filter(f -> f.code.equals(FormValueTypePolicy.INVALID_TYPE)).toList();
        assertEquals(2, invalid.size());
        assertEquals(Set.of("Parameter.Payload", "Attribute.Payload"),
            invalid.stream().map(f -> f.path).collect(java.util.stream.Collectors.toSet()));
        assertTrue(invalid.stream().allMatch(f -> f.severity.equals("error")));
        param.setValueType(types());
        attr.setValueType(types("ValueTable"));
        assertFalse(FormModelValidator.validate(form).stream()
            .anyMatch(f -> f.code.equals(FormValueTypePolicy.INVALID_TYPE)));
    }

    @Test
    public void validatorDoesNotReportValidWhenPickerFails()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormParameter param = parameter();
        param.setValueType(types("String"));
        form.getParameters().add(param);
        FormValueTypePolicy.typeInfoSource = (owner, feature) -> null;
        assertTrue(FormModelValidator.validate(form).stream()
            .anyMatch(f -> f.code.equals(FormValueTypePolicy.UNAVAILABLE) && f.severity.equals("error")));
    }

    @Test
    public void createParserRefusesBeforeWritingAndNamesRequestedType() throws Exception
    {
        Class<?> propsClass = Class.forName(CreateMetadataTool.class.getName() + "$FormMemberProps");
        var constructor = propsClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method parse = CreateMetadataTool.class.getDeclaredMethod("parseFormMemberProperties",
            List.class, FormElementWriter.Kind.class, MdNameNormalizer.Report.class, propsClass);
        parse.setAccessible(true);
        for (String token : List.of("Parameter", "Attribute"))
        {
            for (String kind : COLLECTIONS)
            {
                JsonObject property = JsonParser.parseString("{name:'valueType',value:{types:[{kind:'"
                    + kind + "'}]}}").getAsJsonObject();
                String result = (String)parse.invoke(new CreateMetadataTool(), List.of(property),
                    FormElementWriter.Kind.valueOf(token.toUpperCase(java.util.Locale.ROOT)),
                    new MdNameNormalizer.Report(true), constructor.newInstance());
                JsonObject json = JsonParser.parseString(result).getAsJsonObject();
                assertFalse(result, json.get("success").getAsBoolean());
                assertTrue(result, result.contains(kind));
                assertTrue(result, result.contains("without a type"));
                assertTrue(result, result.contains("arbitrary"));
            }
        }
    }

    @Test
    public void modifyPreparesWholeBatchWithoutChangingParameter() throws Exception
    {
        FormParameter param = parameter();
        param.setComment("original");
        param.setValueType(types("String"));
        Method prepare = ModifyMetadataTool.class.getDeclaredMethod("prepareFormMemberChanges",
            MetadataScope.class, Version.class, EObject.class, List.class, MdNameNormalizer.Report.class);
        prepare.setAccessible(true);
        for (String kind : COLLECTIONS)
        {
            JsonObject comment = JsonParser.parseString("{name:'comment',value:'must not be written'}")
                .getAsJsonObject();
            JsonObject type = JsonParser.parseString("{name:'valueType',value:{types:[{kind:'String'},{kind:'"
                + kind + "'}]}}").getAsJsonObject();
            try
            {
                prepare.invoke(writer(), null, Version.V8_3_27, param,
                    List.of(comment, type), new MdNameNormalizer.Report(true));
                fail("must refuse " + kind);
            }
            catch (InvocationTargetException e)
            {
                String result = FormValidationException.jsonOf(e);
                assertNotNull(e.getCause().toString(), result);
                assertTrue(result, result.contains("type picker"));
                assertTrue(result, result.contains(kind));
            }
            assertEquals("original", param.getComment());
            assertEquals("String", McoreUtil.getTypeName(param.getValueType().getTypes().get(0)));
        }
    }

    @Test
    public void modifyAcceptsOfferedTypeAndRejectsAttributeTypeAlias() throws Exception
    {
        FormAttribute attr = FormFactory.eINSTANCE.createFormAttribute();
        attr.setName("Payload");
        attr.setValueType(types("String"));
        Method prepare = ModifyMetadataTool.class.getDeclaredMethod("prepareFormMemberChanges",
            MetadataScope.class, Version.class, EObject.class, List.class, MdNameNormalizer.Report.class);
        prepare.setAccessible(true);
        JsonObject allowed = JsonParser.parseString("{name:'valueType',value:{types:[{kind:'String'}]}}")
            .getAsJsonObject();
        List<?> changes = (List<?>)prepare.invoke(writer(), null, Version.V8_3_27, parameter(),
            List.of(allowed), new MdNameNormalizer.Report(true));
        assertEquals(1, changes.size());
        JsonObject refused = JsonParser.parseString("{name:'type',value:{types:[{kind:'Array'}]}}")
            .getAsJsonObject();
        try
        {
            prepare.invoke(writer(), null, Version.V8_3_27, attr,
                List.of(refused), new MdNameNormalizer.Report(true));
            fail("the attribute type alias must use the same picker guard");
        }
        catch (InvocationTargetException e)
        {
            String result = FormValidationException.jsonOf(e);
            assertNotNull(e.getCause().toString(), result);
            assertTrue(result, result.contains("Array") && result.contains("attribute type picker"));
        }
        assertEquals("String", McoreUtil.getTypeName(attr.getValueType().getTypes().get(0)));
    }

    private static ModifyMetadataTool writer()
    {
        IEObjectProvider platformTypes = mock(IEObjectProvider.class);
        when(platformTypes.createProxy(anyString())).thenAnswer(call ->
            types((String)call.getArgument(0)).getTypes().get(0));
        return new ModifyMetadataTool()
        {
            @Override
            protected MetadataTypeBuilder.Result buildTypeDescription(JsonElement spec, Configuration config,
                MetadataScope scope, Version version, boolean extension, MetadataTypeBuilder.TypeTarget target)
            {
                return MetadataTypeBuilder.buildWithProvider(spec, platformTypes, config, scope, extension, target);
            }
        };
    }

    private static FormParameter parameter()
    {
        FormParameter param = FormFactory.eINSTANCE.createFormParameter();
        param.setName("Payload");
        return param;
    }

    private static TypeDescription types(String... names)
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        for (String name : names)
        {
            Type type = McoreFactory.eINSTANCE.createType();
            type.setName(name);
            td.getTypes().add(type);
        }
        return td;
    }
}
