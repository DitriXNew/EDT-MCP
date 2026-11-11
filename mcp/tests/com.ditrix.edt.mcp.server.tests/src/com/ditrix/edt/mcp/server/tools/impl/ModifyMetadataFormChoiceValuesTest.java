/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.*;
import com.google.gson.*;

/** Exercises the actual tool preparation, ext-info dispatch and assignable read surface. */
public class ModifyMetadataFormChoiceValuesTest
{
    private static JsonObject property(String name, String raw)
    {
        JsonObject property = new JsonObject(); property.addProperty("name", name);
        property.add("value", JsonParser.parseString(raw)); return property;
    }

    private static FormField input()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField(); field.setName("Выбор");
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
        form.getItems().add(field); return field;
    }

    @SuppressWarnings("unchecked")
    private static List<ModifyMetadataTool.HolderChange> prepare(EObject field, List<JsonObject> properties) throws Exception
    {
        Method method = ModifyMetadataTool.class.getDeclaredMethod("prepareFormMemberChanges", MetadataScope.class,
            Version.class, EObject.class, List.class, MdNameNormalizer.Report.class);
        method.setAccessible(true);
        return (List<ModifyMetadataTool.HolderChange>)method.invoke(new ModifyMetadataTool(), null, null,
            field, properties, new MdNameNormalizer.Report(true));
    }

    private static void apply(FormField field, List<JsonObject> properties) throws Exception
    {
        for (var change : prepare(field, properties))
        {
            assertTrue(ModifyMetadataTool.resolveFormHolder(field, change.change().featureName()).onExtInfo);
            change.change().applyTo(FormElementWriter.ensureExtInfo(field.eContainer(), field), null);
        }
    }

    private static String refusal(EObject field, List<JsonObject> properties) throws Exception
    {
        try { prepare(field, properties); fail("Expected tool refusal"); return null; }
        catch (InvocationTargetException e)
        {
            String message = FormValidationException.jsonOf(e);
            assertNotNull("Expected a structured form refusal: " + e.getCause(), message);
            JsonObject result = JsonParser.parseString(message).getAsJsonObject();
            assertFalse(message, result.get("success").getAsBoolean());
            return message;
        }
    }

    @Test public void bothListsDispatchAndAssignableCurrentValuesFeedTheWriter() throws Exception
    {
        try (ComposerDataTreeFake tree = new ComposerDataTreeFake())
        {
            tree.alias("Object.Company", "Объект.Company", false).install();
            FormField field = input();
            apply(field, List.of(property("choiceParameters", "[{name:'Отбор.Free',value:{kind:'string',value:'Текст'}}]"),
                property("choiceParameterLinks", "[{name:'Free',dataPath:'Объект.Company',changeMode:'DontChange'}]")));
            String text = GetMetadataDetailsTool.formatAssignable("Catalog.X.Form.Y.Field.Выбор", field);
            assertTrue(text, text.contains("| choiceParameters | FORM_CHOICE_VALUES | [{\"name\":\"Отбор.Free\""));
            assertTrue(text, text.contains("\"value\":{\"kind\":\"string\",\"value\":\"Текст\"}"));
            assertTrue(text, text.contains("| choiceParameterLinks | FORM_CHOICE_VALUES | [{\"name\":\"Free\",\"dataPath\":\"Object.Company\",\"changeMode\":\"DontChange\"}] |"));
            var ext = field.getExtInfo();
            for (var info : MetadataPropertyIntrospector.introspect(field, ext))
                if (FormChoiceParameters.isProperty(info.name))
                    apply(field, List.of(property(info.name, info.currentValue)));
            assertEquals(text, GetMetadataDetailsTool.formatAssignable("Catalog.X.Form.Y.Field.Выбор", field));
            apply(field, List.of(property("choiceParameters", "[]"), property("choiceParameterLinks", "[]")));
            text = GetMetadataDetailsTool.formatAssignable("Catalog.X.Form.Y.Field.Выбор", field);
            assertTrue(text, text.contains("| choiceParameters | FORM_CHOICE_VALUES | [] |"));
            assertTrue(text, text.contains("| choiceParameterLinks | FORM_CHOICE_VALUES | [] |"));
        }
    }

    @Test public void missingExtInfoIsOfferedAndCreatedOnlyWhenApplying() throws Exception
    {
        FormField field = input(); field.setExtInfo(null);
        var info = MetadataPropertyIntrospector.findFeature(field,
            FormFactory.eINSTANCE.createInputFieldExtInfo(), "choiceParameters");
        assertNotNull(info); assertTrue(info.onExtInfo);
        prepare(field, List.of(property("choiceParameters", "[]")));
        assertNull(field.getExtInfo());
        apply(field, List.of(property("choiceParameters", "[{name:'',value:{kind:'undefined'}}]")));
        assertEquals(1, ((InputFieldExtInfo)field.getExtInfo()).getChoiceParameters().size());
    }

    @Test public void bothListNamesRefuseNonInputTargetsWithAnActionableToolError() throws Exception
    {
        FormField field = input(); field.setType(ManagedFormFieldType.LABEL_FIELD);
        for (String name : List.of("choiceParameters", "choiceParameterLinks"))
            for (EObject target : List.of(field, FormFactory.eINSTANCE.createFormGroup()))
            {
                String error = refusal(target, List.of(property(name, "[]")));
                assertTrue(error, error.contains(name));
                assertTrue(error, error.contains("InputField"));
                assertTrue(error, error.contains("get_metadata_details"));
            }
    }

    @Test public void adoptedFormRefusalTravelsThroughToolResult() throws Exception
    {
        FormField field = input(); CatalogForm mdForm = MdClassFactory.eINSTANCE.createCatalogForm();
        mdForm.setName("Adopted"); mdForm.setObjectBelonging(ObjectBelonging.ADOPTED);
        ((Form)field.eContainer()).setMdForm(mdForm);
        for (String name : List.of("choiceParameters", "choiceParameterLinks"))
        {
            String error = refusal(field, List.of(property(name, "[]")));
            assertTrue(error, error.contains("adopted form"));
            assertTrue(error, error.contains("base configuration"));
        }
    }

    @Test public void badValueAfterAValidPropertyNeverMutatesTheInputField() throws Exception
    {
        FormField field = input();
        String error = refusal(field, List.of(property("choiceParameters", "[{name:'X',value:{kind:'null'}}]"),
            property("choiceParameterLinks", "[{name:'X',dataPath:4,changeMode:'Clear'}]")));
        assertTrue(error, error.contains("dataPath"));
        assertTrue(((InputFieldExtInfo)field.getExtInfo()).getChoiceParameters().isEmpty());
    }

    @Test public void existingTypeExtInfoGuardAlsoCoversBothLists() throws Exception
    {
        FormField field = input();
        for (String name : List.of("choiceParameters", "choiceParameterLinks"))
        {
            String error = refusal(field, List.of(property("type", "'LabelField'"), property(name, "[]")));
            assertTrue(error, error.contains("cannot be combined"));
            assertEquals(ManagedFormFieldType.INPUT_FIELD, field.getType());
        }
    }
}
