/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com.ditrix.edt.mcp.server.utils.*;
import com.google.gson.*;

public class ModifyMetadataMemberValuesTest
{
    private static JsonElement json(String text) { return JsonParser.parseString(text); }
    private static Catalog catalog()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration(); config.setName("Config");
        new ResourceImpl(URI.createURI("test:/config")).getContents().add(config);
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog(); catalog.setName("Товары");
        config.getCatalogs().add(catalog); return catalog;
    }
    private static CatalogAttribute member(Catalog owner, String name)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute(); attribute.setName(name);
        owner.getAttributes().add(attribute); return attribute;
    }
    private static DerivedField field(FieldSource owner, MdObject source)
    {
        DerivedField field = McoreFactory.eINSTANCE.createDerivedField(); field.setName(source.getName());
        field.setNameRu(source.getName()); field.setFieldIdPrefix("Attribute"); field.setSource(source);
        field.getSourceTypes().add(SourceType.CHOICE_PARAM_LINKS); owner.getFields().add(field); return field;
    }

    @Test public void fillAvailabilityFollowsTheMetamodelWithoutInventingMissingProperties()
    {
        int available = 0;
        for (EClassifier classifier : MdClassPackage.eINSTANCE.getEClassifiers())
        {
            if (!(classifier instanceof EClass kind) || kind.isAbstract()) continue;
            boolean has = kind.getEStructuralFeature("fillValue") != null;
            var info = MetadataPropertyIntrospector.introspect(MdClassFactory.eINSTANCE.create(kind)).stream()
                .filter(p -> p.name.equals("fillValue")).findFirst().orElse(null);
            assertEquals(kind.getName(), has, info != null);
            if (has) { available++; assertEquals(MetadataPropertyIntrospector.ValueKind.MEMBER_VALUES, info.valueKind); }
        }
        assertTrue(available > 10);
        for (EObject member : List.of(MdClassFactory.eINSTANCE.createCatalogAttribute(),
            MdClassFactory.eINSTANCE.createCommonAttribute(), MdClassFactory.eINSTANCE.createStandardAttribute(),
            MdClassFactory.eINSTANCE.createInformationRegisterDimension()))
            assertNotNull(member.eClass().getName(), MetadataPropertyIntrospector.findFeature(member, null, "fillValue"));
        for (EObject member : List.of(MdClassFactory.eINSTANCE.createTabularSectionAttribute(),
            MdClassFactory.eINSTANCE.createAccumulationRegisterDimension(), MdClassFactory.eINSTANCE.createConstant()))
            assertNull(member.eClass().getName(), MetadataPropertyIntrospector.findFeature(member, null, "fillValue"));
    }

    @Test public void freeNamesAndUnmatchedChoiceValuesAreWrittenAndListsAreReplacedThenCleared()
    {
        CatalogAttribute member = member(catalog(), "Атрибут");
        var feature = member.eClass().getEStructuralFeature("choiceParameters");
        for (String name : List.of("Filter.Unknown", "Отбор.Неизвестный", "", "ChoiceFormParameter"))
        {
            JsonArray raw = new JsonArray(); JsonObject item = new JsonObject(); item.addProperty("name", name);
            item.add("value", json("{kind:'fixedArray',values:[{kind:'number',value:1},{kind:'string',value:'Текст'}]}"));
            raw.add(item);
            MetadataMemberValues.prepare(null, member, "choiceParameters", raw).apply(member, feature, null);
            assertEquals(1, member.getChoiceParameters().size());
            assertEquals(name, member.getChoiceParameters().get(0).getName());
            assertEquals(raw, MetadataMemberValues.render("choiceParameters", member.getChoiceParameters()));
        }
        MetadataMemberValues.prepare(null, member, "choiceParameters", json("[]")).apply(member, feature, null);
        assertTrue(member.getChoiceParameters().isEmpty());
    }

    @Test public void linksResolveBilinguallyAcrossTabularContextAndListValidTargets()
    {
        Catalog owner = catalog(); CatalogAttribute other = member(owner, "Другой"); field(owner, other);
        CatalogTabularSection section = MdClassFactory.eINSTANCE.createCatalogTabularSection(); section.setName("Строки");
        owner.getTabularSections().add(section);
        TabularSectionAttribute target = MdClassFactory.eINSTANCE.createTabularSectionAttribute();
        target.setName("Выбор"); section.getAttributes().add(target);
        TabularSectionAttribute sibling = MdClassFactory.eINSTANCE.createTabularSectionAttribute();
        sibling.setName("Сосед"); section.getAttributes().add(sibling); field(section, sibling); field(section, target);
        for (String fqn : List.of("Catalog.Товары.Attribute.Другой", "Справочник.Товары.Реквизит.Другой",
            "Catalog.Товары.TabularSection.Строки.Attribute.Сосед", "Справочник.Товары.ТабличнаяЧасть.Строки.Реквизит.Сосед"))
        {
            JsonElement raw = json("[{name:'Отбор.Произвольный',field:'" + fqn + "',changeMode:'DontChange'}]");
            var plan = MetadataMemberValues.prepare(null, target, "choiceParameterLinks", raw);
            plan.apply(target, target.eClass().getEStructuralFeature("choiceParameterLinks"), null);
            assertEquals(1, target.getChoiceParameterLinks().size());
            assertTrue(plan.entries().get(0).address().startsWith("Catalog.Товары."));
        }
        try
        {
            MetadataMemberValues.prepare(null, target, "choiceParameterLinks",
                json("[{name:'X',field:'Catalog.Товары.Attribute.Missing',changeMode:'Clear'}]")); fail();
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("Catalog.Товары.Attribute.Другой"));
            assertTrue(e.getMessage(), e.getMessage().contains("TabularSection.Строки.Attribute.Сосед"));
        }
        MetadataMemberValues.prepare(null, target, "choiceParameterLinks", json("[]"))
            .apply(target, target.eClass().getEStructuralFeature("choiceParameterLinks"), null);
        assertTrue(target.getChoiceParameterLinks().isEmpty());
    }

    @Test public void constantLinksUseTheNativeConstantAddressAndExcludeSelf() throws Exception
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration(); config.setName("Config");
        // Configuration collections reference BM top objects; the platform provider supplies their configuration.
        // Use the metadata bundle's own loader for this test-only provider seam (the implementation is not API).
        Class<?> provider = com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil.class.getClassLoader()
            .loadClass("com._1c.g5.v8.dt.metadata.mdclass.impl.ConfigurationProvider");
        java.lang.reflect.Field singleton = provider.getField("INSTANCE");
        Object saved = singleton.get(null);
        singleton.set(null, org.mockito.Mockito.mock(provider, invocation -> config));
        try
        {
            Constant source = MdClassFactory.eINSTANCE.createConstant(); source.setName("Другая"); config.getConstants().add(source);
            Constant target = MdClassFactory.eINSTANCE.createConstant(); target.setName("Выбор"); config.getConstants().add(target);
            for (Constant constant : List.of(source, target))
            {
                DerivedField field = McoreFactory.eINSTANCE.createDerivedField(); field.setSource(constant);
                field.setName("Constant." + constant.getName()); field.setNameRu("Константа." + constant.getName());
                field.setFieldIdPrefix("Constant"); field.getSourceTypes().add(SourceType.CHOICE_PARAM_LINKS);
                constant.getFields().add(field);
            }
            for (String address : List.of("Constant.Другая", "Константа.Другая"))
            {
                var plan = MetadataMemberValues.prepare(null, target, "choiceParameterLinks",
                    json("[{name:'Free',field:'" + address + "',changeMode:'DontChange'}]"));
                assertEquals("Constant.Другая", plan.entries().get(0).address());
                plan.apply(target, target.eClass().getEStructuralFeature("choiceParameterLinks"), null);
                assertEquals(1, target.getChoiceParameterLinks().size());
            }
            assertEquals(1, FieldListSupport.choiceLinkTargets(target).size());
        }
        finally { singleton.set(null, saved); }
    }

    @Test public void writesWithoutFillValueDoNotReadTheMemberType()
    {
        CatalogAttribute target = org.mockito.Mockito.mock(CatalogAttribute.class);
        var list = ModifyMetadataTool.PreparedChange.memberValues(MdClassPackage.Literals.BASIC_FEATURE__CHOICE_PARAMETERS,
            new MetadataMemberValues.Plan("choiceParameters", null, List.of()));
        assertNull(ModifyMetadataTool.memberValueTypeError(target, List.of(list)));
        org.mockito.Mockito.verifyZeroInteractions(target);
    }

    @Test public void adoptedMembersRefuseAllThreePropertiesWithARepairInBothNameLanguages()
    {
        for (String name : List.of("Attribute", "Реквизит"))
        {
            CatalogAttribute member = member(catalog(), name); member.setObjectBelonging(ObjectBelonging.ADOPTED);
            for (String property : List.of("fillValue", "choiceParameters", "choiceParameterLinks"))
            {
                try { MetadataMemberValues.prepare(null, member, property, json("[]")); fail(property); }
                catch (IllegalArgumentException e)
                {
                    assertTrue(e.getMessage(), e.getMessage().contains(name));
                    assertTrue(e.getMessage(), e.getMessage().contains("base configuration"));
                }
            }
        }
    }

    @Test public void toolPreparationActuallyDispatchesTheNewPropertiesAndReturnsActionableErrors() throws Exception
    {
        var method = ModifyMetadataTool.class.getDeclaredMethod("validateAndPrepare", org.eclipse.core.resources.IProject.class,
            MetadataScope.class, Configuration.class, com._1c.g5.v8.dt.platform.version.Version.class,
            MdObject.class, List.class, List.class, MdNameNormalizer.Report.class, boolean.class);
        method.setAccessible(true);
        CatalogAttribute attribute = member(catalog(), "Атрибут");
        List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
        Object error = method.invoke(new ModifyMetadataTool(), null, null, null, null, attribute,
            List.of(json("{name:'fillValue',value:{kind:'string',value:'X'}}").getAsJsonObject(),
                json("{name:'choiceParameters',value:[]}").getAsJsonObject(),
                json("{name:'choiceParameterLinks',value:[]}").getAsJsonObject()),
            changes, new MdNameNormalizer.Report(true), false);
        assertNull(error); assertEquals(3, changes.size());
        ModifyMetadataTool.applyChanges(attribute, changes, null, "Catalog.Товары.Attribute.Атрибут");
        assertEquals("X", ((StringValue)attribute.getFillValue()).getValue());
        error = method.invoke(new ModifyMetadataTool(), null, null, null, null, attribute,
            List.of(json("{name:'fillValue',value:{kind:'fixedArray',values:[]}}").getAsJsonObject()),
            new ArrayList<>(), new MdNameNormalizer.Report(true), false);
        assertFalse(JsonParser.parseString((String)error).getAsJsonObject().get("success").getAsBoolean());
        assertTrue(error.toString(), error.toString().contains("scalar"));
    }

    @Test public void finalBatchTypeChecksBeforeMutationAndTypeRunsBeforeMemberValues()
    {
        CatalogAttribute member = member(catalog(), "A");
        TypeDescription string = McoreFactory.eINSTANCE.createTypeDescription();
        Type s = McoreFactory.eINSTANCE.createType(); s.setName("String"); string.getTypes().add(s); member.setType(string);
        var fill = ModifyMetadataTool.PreparedChange.memberValues(member.eClass().getEStructuralFeature("fillValue"),
            MetadataMemberValues.prepare(null, member, "fillValue", json("{kind:'number',value:8}")));
        assertTrue(ModifyMetadataTool.memberValueTypeError(member, List.of(fill)).contains("does not match"));
        TypeDescription number = McoreFactory.eINSTANCE.createTypeDescription();
        Type n = McoreFactory.eINSTANCE.createType(); n.setName("Number"); number.getTypes().add(n);
        var type = ModifyMetadataTool.PreparedChange.typeDescription(member.eClass().getEStructuralFeature("type"), number);
        assertNull(ModifyMetadataTool.memberValueTypeError(member, List.of(fill, type)));
        assertNull(ModifyMetadataTool.memberValueTypeError(member, List.of(type, fill)));
        // The unchanged type uses the plain-set branch, allowing the actual apply pipeline headlessly.
        member.setType(number);
        ModifyMetadataTool.applyChanges(member, List.of(fill, type), null, "Catalog.Товары.Attribute.A");
        assertEquals(8, ((NumberValue)member.getFillValue()).getValue().intValueExact());
    }
}
