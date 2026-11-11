/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com.google.gson.*;

public class FormChoiceParametersTest
{
    private final Supplier<IDataSourceInfoAssociationService> saved = FormElementWriter.dataSourcesSource;
    private final IDataSourceInfoAssociationService service = mock(IDataSourceInfoAssociationService.class);
    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    @After public void restore() { FormElementWriter.dataSourcesSource = saved; }

    private static FormField input()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField(); field.setName("Выбор");
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        field.setExtInfo(FormFactory.eINSTANCE.createInputFieldExtInfo());
        form.getItems().add(field);
        return field;
    }

    private static InputFieldExtInfo ext(FormField field) { return (InputFieldExtInfo)field.getExtInfo(); }
    private static DataPath path(String text) { return ComposerDataTreeFake.dataPath(text); }

    private PropertyInfo info(String canonical)
    {
        PropertyInfo info = mock(PropertyInfo.class);
        when(info.getDataPath(ScriptVariant.ENGLISH)).thenAnswer(call -> path(canonical));
        return info;
    }

    private void installPaths()
    {
        PropertyInfo root = info("Object");
        PropertyInfo company = info("Object.Company");
        PropertyInfo goods = info("Товары.Номенклатура");
        when(root.getPropertyInfos()).thenReturn(List.of(company));
        when(service.findPropertyInfo(any(Form.class), any(AbstractDataPath.class))).thenAnswer(call -> {
            String key = String.join(".", ((AbstractDataPath)call.getArgument(1)).getSegments());
            return switch (key)
            {
                case "Object", "Объект" -> root;
                case "Object.Company", "Объект.Company", "Объект.Организация" -> company;
                case "Товары.Номенклатура" -> goods;
                default -> null;
            };
        });
        FormElementWriter.dataSourcesSource = () -> service;
    }

    @Test public void everyScalarAndNestedArrayIsWrappedAndRoundTripsWithoutEditingOnRead()
    {
        FormField field = input();
        String property = "choiceParameters";
        for (String value : List.of("{kind:'string',value:'Текст'}", "{kind:'string',value:''}",
            "{kind:'boolean',value:true}", "{kind:'number',value:12.5}",
            "{kind:'date',value:'2026-01-02T03:04:05'}", "{kind:'null'}", "{kind:'undefined'}",
            "{kind:'fixedArray',values:[{kind:'number',value:1},{kind:'fixedArray',values:[{kind:'null'}]}]}"))
        {
            JsonElement raw = json("[{name:'Отбор.Free',value:" + value + "}]");
            FormChoiceParameters.prepare(null, field, property, raw).apply(ext(field), null);
            Value stored = ext(field).getChoiceParameters().get(0).getValue();
            assertWrapped(stored);
            JsonArray rendered = FormChoiceParameters.render(ext(field).getChoiceParameters());
            assertEquals(raw, rendered);
            assertSame(stored, ext(field).getChoiceParameters().get(0).getValue());
            FormChoiceParameters.prepare(null, field, property, rendered).apply(ext(field), null);
            assertEquals(raw, FormChoiceParameters.render(ext(field).getChoiceParameters()));
        }
    }

    private static void assertWrapped(Value value)
    {
        assertTrue(value.eClass().getName(), value instanceof FormChoiceListDesTimeValue);
        Value raw = ((FormChoiceListDesTimeValue)value).getValue();
        assertNotNull(raw);
        if (raw instanceof FixedArrayValue array)
            for (Value child : array.getValues()) assertWrapped(child);
    }

    @Test public void fixedArrayReferencesRefetchUrisAndReadBackAsPlainValueSpecs()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration(); configuration.setName("Config");
        new ResourceImpl(URI.createURI("test:/config")).getContents().add(configuration);
        com._1c.g5.v8.dt.metadata.mdclass.Enum owner = MdClassFactory.eINSTANCE.createEnum(); owner.setName("Цвета");
        configuration.getEnums().add(owner);
        com._1c.g5.v8.dt.metadata.mdclass.EnumValue red = MdClassFactory.eINSTANCE.createEnumValue(); red.setName("Красный");
        owner.getEnumValues().add(red);
        MetadataScope scope = MetadataScope.ofConfiguration(configuration);
        MetadataValueCodec.Reference reference = MetadataValueCodec.resolve(scope, "Enum.Цвета.EnumValue.Красный");
        FormField field = input();
        JsonElement raw = json("[{name:'',value:{kind:'fixedArray',values:["
            + "{kind:'ref',ref:'Перечисление.Цвета.ЗначениеПеречисления.Красный'},"
            + "{kind:'fixedArray',values:[{kind:'ref',ref:'Enum.Цвета.EnumValue.Красный'}]}]}}]");
        var plan = FormChoiceParameters.prepare(scope, field, "choiceParameters", raw);
        // A distinct write-transaction object proves the preparation object is never retained.
        com._1c.g5.v8.dt.metadata.mdclass.EnumValue fresh = MdClassFactory.eINSTANCE.createEnumValue();
        fresh.setName("Красный"); owner.getEnumValues().set(0, fresh);
        IBmTransaction tx = mock(IBmTransaction.class);
        when(tx.getObjectByUri(reference.uri())).thenReturn((IBmObject)fresh);
        plan.apply(ext(field), tx);
        Value stored = ext(field).getChoiceParameters().get(0).getValue(); assertWrapped(stored);
        FixedArrayValue array = (FixedArrayValue)((FormChoiceListDesTimeValue)stored).getValue();
        assertSame(fresh, ((ReferenceValue)((FormChoiceListDesTimeValue)array.getValues().get(0)).getValue()).getValue());
        JsonElement canonical = json(raw.toString().replace("Перечисление.Цвета.ЗначениеПеречисления.Красный",
            "Enum.Цвета.EnumValue.Красный"));
        assertEquals(canonical, FormChoiceParameters.render(ext(field).getChoiceParameters()));
        verify(tx, times(2)).getObjectByUri(reference.uri());
        FormChoiceParameters.prepare(scope, field, "choiceParameters", canonical).apply(ext(field), tx);
        assertEquals(canonical, FormChoiceParameters.render(ext(field).getChoiceParameters()));
        when(tx.getObjectByUri(reference.uri())).thenReturn(null);
        try { plan.apply(ext(field), tx); fail(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains("no longer resolves")); }
        assertEquals(canonical, FormChoiceParameters.render(ext(field).getChoiceParameters()));
    }

    @Test public void pathsResolveInBothDialectsAndBothChangeModesRoundTripThenClear()
    {
        installPaths(); FormField field = input();
        for (String requested : List.of("Object.Company", "Объект.Company", "Объект.Организация", "Товары.Номенклатура"))
            for (String mode : List.of("Clear", "DontChange"))
            {
                JsonElement raw = json("[{name:'Filter.Unknown',dataPath:'" + requested + "',changeMode:'" + mode + "'}]");
                var plan = FormChoiceParameters.prepare(null, field, "choiceParameterLinks", raw);
                plan.apply(ext(field), null);
                String canonical = requested.startsWith("Товары.") ? requested : "Object.Company";
                assertEquals(canonical, String.join(".", ext(field).getChoiceParameterLinks().get(0).getDatapath().getSegments()));
                JsonArray current = FormChoiceParameters.render(ext(field).getChoiceParameterLinks());
                JsonArray expected = raw.getAsJsonArray().deepCopy();
                expected.get(0).getAsJsonObject().addProperty("dataPath", canonical);
                assertEquals(expected, current);
                FormChoiceParameters.prepare(null, field, "choiceParameterLinks", current).apply(ext(field), null);
                assertEquals(1, ext(field).getChoiceParameterLinks().size());
            }
        FormChoiceParameters.prepare(null, field, "choiceParameterLinks", json("[]")).apply(ext(field), null);
        assertEquals(json("[]"), FormChoiceParameters.render(ext(field).getChoiceParameterLinks()));
    }

    @Test public void linksRecheckPathsOnApplyAndKeepTheOldListWhenTheTargetDisappears()
    {
        installPaths(); FormField field = input();
        var plan = FormChoiceParameters.prepare(null, field, "choiceParameterLinks",
            json("[{name:'X',dataPath:'Object.Company',changeMode:'Clear'}]"));
        plan.apply(ext(field), null);
        JsonArray before = FormChoiceParameters.render(ext(field).getChoiceParameterLinks());
        when(service.findPropertyInfo(any(Form.class), any(AbstractDataPath.class))).thenReturn(null);
        try { plan.apply(ext(field), null); fail(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains("Object.Company")); }
        assertEquals(before, FormChoiceParameters.render(ext(field).getChoiceParameterLinks()));
    }

    @Test public void unresolvedPathListsValidSiblingPathsAndNeverAcceptsASynonymOrMetadataFqn()
    {
        installPaths(); FormField field = input();
        for (String bad : List.of("Object.Missing", "Объект.Company synonym", "Catalog.Company.Attribute.Name", "Object..Company"))
        {
            try
            {
                FormChoiceParameters.prepare(null, field, "choiceParameterLinks",
                    json("[{name:'X',dataPath:'" + bad + "',changeMode:'Clear'}]")); fail(bad);
            }
            catch (IllegalArgumentException e)
            {
                assertTrue(e.getMessage(), e.getMessage().contains(bad));
                assertTrue(e.getMessage(), e.getMessage().contains("valid form data path"));
                if (bad.startsWith("Object.") || bad.startsWith("Объект."))
                    assertTrue(e.getMessage(), e.getMessage().contains("Object.Company"));
            }
        }
        assertTrue(ext(field).getChoiceParameterLinks().isEmpty());
    }

    @Test public void freeNamesAreNotValidatedAndListsReplaceRatherThanAppend()
    {
        FormField field = input();
        for (String name : List.of("", "ChoiceFormParameter", "Отбор.Неизвестный", "Filter.Any | name"))
        {
            JsonArray raw = new JsonArray(); JsonObject entry = new JsonObject(); entry.addProperty("name", name);
            entry.add("value", json("{kind:'boolean',value:true}")); raw.add(entry);
            FormChoiceParameters.prepare(null, field, "choiceParameters", raw).apply(ext(field), null);
            assertEquals(raw, FormChoiceParameters.render(ext(field).getChoiceParameters()));
        }
        FormChoiceParameters.prepare(null, field, "choiceParameters", json("[]")).apply(ext(field), null);
        assertEquals(json("[]"), FormChoiceParameters.render(ext(field).getChoiceParameters()));
    }

    @Test public void adoptedFormsRefuseBothListsAtPreparationAndApply()
    {
        FormField field = input(); Form form = (Form)field.eContainer();
        CatalogForm mdForm = MdClassFactory.eINSTANCE.createCatalogForm(); mdForm.setName("Форма");
        form.setMdForm(mdForm);
        for (String property : List.of("choiceParameters", "choiceParameterLinks"))
        {
            mdForm.setObjectBelonging(ObjectBelonging.NATIVE);
            var plan = FormChoiceParameters.prepare(null, field, property, json("[]"));
            mdForm.setObjectBelonging(ObjectBelonging.ADOPTED);
            for (Runnable operation : List.<Runnable>of(
                () -> FormChoiceParameters.prepare(null, field, property, json("[]")), () -> plan.apply(ext(field), null)))
            {
                try { operation.run(); fail(property); }
                catch (IllegalArgumentException e)
                {
                    assertTrue(e.getMessage(), e.getMessage().contains(property));
                    assertTrue(e.getMessage(), e.getMessage().contains("adopted form"));
                    assertTrue(e.getMessage(), e.getMessage().contains("base configuration"));
                    assertTrue(e.getMessage(), e.getMessage().contains("extension-owned form"));
                }
            }
        }
    }

    @Test public void malformedSpecsAndLinkShapesFailBeforeReplacingAnything()
    {
        installPaths(); FormField field = input();
        for (String raw : List.of("{}", "[null]", "[{name:4,value:{kind:'null'}}]",
            "[{name:'X',value:{kind:'boolean',value:'bad'}}]", "[{name:'X',value:{kind:'fixedArray',values:4}}]",
            "[{name:'X',value:{kind:'fixedArray',values:[{kind:'ref',ref:'Enum.No.EnumValue.X'}]}}]",
            "[{name:'X',value:{kind:'string',value:'x',typo:1}}]"))
        {
            try { FormChoiceParameters.prepare(MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()),
                field, "choiceParameters", json(raw)); fail(raw); }
            catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains("choiceParameters")); }
        }
        for (String raw : List.of("[{name:'X',field:'Object.Company',changeMode:'Clear'}]",
            "[{name:'X',dataPath:'Object.Company',changeMode:'wrong'}]", "[{name:'X',dataPath:5,changeMode:'Clear'}]"))
        {
            try { FormChoiceParameters.prepare(null, field, "choiceParameterLinks", json(raw)); fail(raw); }
            catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains("choiceParameterLinks")); }
        }
        assertTrue(ext(field).getChoiceParameters().isEmpty());
        assertTrue(ext(field).getChoiceParameterLinks().isEmpty());
    }

    @Test public void nonInputItemsRefuseEvenWithStaleInputExtInfo()
    {
        FormField field = input(); field.setType(ManagedFormFieldType.LABEL_FIELD);
        for (String property : List.of("choiceParameters", "choiceParameterLinks"))
            for (org.eclipse.emf.ecore.EObject item : List.of(field, FormFactory.eINSTANCE.createFormGroup()))
            {
                try { FormChoiceParameters.prepare(null, item, property, json("[]")); fail(); }
                catch (IllegalArgumentException e)
                {
                    assertTrue(e.getMessage(), e.getMessage().contains("not an input field"));
                    assertTrue(e.getMessage(), e.getMessage().contains("get_metadata_details"));
                }
            }
    }
}
