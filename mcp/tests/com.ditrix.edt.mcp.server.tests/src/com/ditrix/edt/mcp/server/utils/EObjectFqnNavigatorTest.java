/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/** The Navigator's persisted addresses use EClass tokens, unlike CRUD kind aliases. */
public class EObjectFqnNavigatorTest
{
    private static final MdClassFactory FACTORY = MdClassFactory.eINSTANCE;

    @Test
    public void incompletePairDoesNotResolveToTopObject()
    {
        for (String name : new String[] { "Files", "Товары" })
        {
            var catalog = named(FACTORY.createCatalog(), name);
            String top = "Catalog." + name;
            assertSame(catalog, EObjectFqnNavigator.resolveNestedObject(catalog, top.split("\\."), 2));
            for (String kind : new String[] { "Attribute", "CatalogAttribute" })
                assertNull(top + "." + kind, EObjectFqnNavigator.resolveNestedObject(catalog,
                    (top + "." + kind).split("\\."), 2));
        }
    }

    @Test
    public void incompletePairAfterMemberDoesNotResolveToMember()
    {
        var catalog = named(FACTORY.createCatalog(), "Files");
        var attribute = named(FACTORY.createCatalogAttribute(), "Width");
        catalog.getAttributes().add(attribute);
        String stored = TagUtils.extractFqn((EObject)attribute);
        assertSame(attribute, EObjectFqnNavigator.resolveNestedObject(catalog, stored.split("\\."), 2));
        assertNull(EObjectFqnNavigator.resolveNestedObject(catalog, (stored + ".Attribute").split("\\."), 2));
    }

    @Test
    public void incompletePairAfterSectionDoesNotResolveToSection()
    {
        var catalog = named(FACTORY.createCatalog(), "Товары");
        var section = named(FACTORY.createCatalogTabularSection(), "Строки");
        catalog.getTabularSections().add(section);
        String stored = TagUtils.extractFqn((EObject)section);
        assertSame(section, EObjectFqnNavigator.resolveNestedObject(catalog, stored.split("\\."), 2));
        assertNull(EObjectFqnNavigator.resolveNestedObject(catalog,
            (stored + ".TabularSectionAttribute").split("\\."), 2));
    }

    @Test
    public void storedCatalogAttributeCannotBePassedToTheCrudResolver()
    {
        var config = FACTORY.createConfiguration();
        var catalog = named(FACTORY.createCatalog(), "Files");
        var attribute = named(FACTORY.createCatalogAttribute(), "Width");
        config.getCatalogs().add(catalog);
        catalog.getAttributes().add(attribute);
        String stored = TagUtils.extractFqn((EObject)attribute);
        assertEquals("Catalog.Files.CatalogAttribute.Width", stored);
        assertSame(attribute, EObjectFqnNavigator.resolveNestedObject(catalog, stored.split("\\."), 2));
        assertNull(MetadataNodeResolver.resolveExisting(config, stored));
        assertSame(attribute, MetadataNodeResolver.resolveExisting(config, "Catalog.Files.Attribute.Width").object);
    }

    @Test
    public void storedTabularSectionMemberNeedsBothRawTypeTokens()
    {
        var config = FACTORY.createConfiguration();
        var catalog = named(FACTORY.createCatalog(), "Files");
        var section = named(FACTORY.createCatalogTabularSection(), "Rows");
        var attribute = named(FACTORY.createTabularSectionAttribute(), "Width");
        config.getCatalogs().add(catalog);
        catalog.getTabularSections().add(section);
        section.getAttributes().add(attribute);
        String stored = TagUtils.extractFqn((EObject)attribute);
        assertEquals("Catalog.Files.CatalogTabularSection.Rows.TabularSectionAttribute.Width", stored);
        assertSame(attribute, EObjectFqnNavigator.resolveNestedObject(catalog, stored.split("\\."), 2));
        assertNull(MetadataNodeResolver.resolveExisting(config, stored));
        assertSame(attribute,
            MetadataNodeResolver.resolveExisting(config, "Catalog.Files.TabularSection.Rows.Attribute.Width").object);
    }

    @Test
    public void storedOwnedFormIsOutsideTheCrudResolversKindVocabulary()
    {
        var config = FACTORY.createConfiguration();
        var catalog = named(FACTORY.createCatalog(), "Files");
        var form = named(FACTORY.createCatalogForm(), "ItemForm");
        config.getCatalogs().add(catalog);
        catalog.getForms().add(form);
        String stored = TagUtils.extractFqn((EObject)form);
        assertEquals("Catalog.Files.CatalogForm.ItemForm", stored);
        assertSame(form, EObjectFqnNavigator.resolveNestedObject(catalog, stored.split("\\."), 2));
        assertNull(MetadataNodeResolver.resolveExisting(config, stored));
        assertNull(MetadataNodeResolver.resolveExisting(config, "Catalog.Files.Form.ItemForm"));
    }

    @Test
    public void russianProgrammaticNamesAndSynonymsKeepTheirStoredIdentity()
    {
        var config = FACTORY.createConfiguration();
        var catalog = named(FACTORY.createCatalog(), "Товары");
        var attribute = named(FACTORY.createCatalogAttribute(), "Ширина");
        attribute.getSynonym().put("en", "Width");
        attribute.getSynonym().put("ru", "Ширина товара");
        config.getCatalogs().add(catalog);
        catalog.getAttributes().add(attribute);
        String stored = TagUtils.extractFqn((EObject)attribute);
        assertEquals("Catalog.Товары.CatalogAttribute.Ширина", stored);
        assertSame(attribute, EObjectFqnNavigator.resolveNestedObject(catalog, stored.split("\\."), 2));
        assertNull(EObjectFqnNavigator.resolveNestedObject(catalog,
            "Catalog.Товары.CatalogAttribute.Width".split("\\."), 2));
        assertNull(MetadataNodeResolver.resolveExisting(config, stored));
        assertSame(attribute,
            MetadataNodeResolver.resolveExisting(config, "Справочник.Товары.Реквизит.Ширина").object);
    }

    @Test
    public void overlappingKindTokensStillResolveToTheSameObject()
    {
        var config = FACTORY.createConfiguration();
        var enumeration = named(FACTORY.createEnum(), "Colors");
        var value = named(FACTORY.createEnumValue(), "Red");
        config.getEnums().add(enumeration);
        enumeration.getEnumValues().add(value);
        String stored = TagUtils.extractFqn((EObject)value);
        assertEquals("Enum.Colors.EnumValue.Red", stored);
        assertSame(value, EObjectFqnNavigator.resolveNestedObject(enumeration, stored.split("\\."), 2));
        assertSame(value, MetadataNodeResolver.resolveExisting(config, stored).object);
    }

    private static <T extends MdObject> T named(T object, String name)
    {
        object.setName(name);
        return object;
    }
}
