/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.eclipse.emf.ecore.EReference;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

public class MetadataOrderingTest
{
    private final MdClassFactory factory = MdClassFactory.eINSTANCE;

    private static List<String> order(List<String> names, String mode, List<String> items,
        String name, String direction, int positions, String before, String after)
    {
        return MetadataOrdering.order(names, mode, items, name, direction, positions, before, after);
    }

    @Test
    public void alphabeticalUsesProgrammaticNamesAndSharedYoNormalizationStably()
    {
        List<String> input = List.of("z", "a", "A", "\u0401\u043b\u043a\u0430", "\u0415\u043b\u043a\u0430", "\u0411");
        assertEquals(List.of("a", "A", "z", "\u0411", "\u0401\u043b\u043a\u0430", "\u0415\u043b\u043a\u0430"),
            order(input, "alphabetical", null, null, null, 1, null, null));
        assertEquals("z", input.get(0));
    }

    @Test
    public void emptyAndSingleListsAreAlreadySorted()
    {
        assertEquals(List.of(), order(List.of(), "alphabetical", null, null, null, 1, null, null));
        assertEquals(List.of("A"), order(List.of("A"), "alphabetical", null, null, null, 1, null, null));
        assertTrue(MetadataOrdering.moved(List.of("A"), List.of("A")).isEmpty());
    }

    @Test
    public void explicitKeepsUnlistedRelativeOrderAndStoredCasing()
    {
        assertEquals(List.of("C", "A", "D", "B"), order(List.of("D", "B", "A", "C"),
            "explicit", List.of("c", "a"), null, null, 1, null, null));
        assertEquals(List.of("B", "A"), order(List.of("B", "A"), "explicit", List.of(), null, null, 1, null, null));
    }

    @Test
    public void explicitRejectsMissingAndDuplicateMembers()
    {
        assertThrows(IllegalArgumentException.class, () -> order(List.of("A"), "explicit", List.of("Missing"),
            null, null, 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> order(List.of("A"), "explicit", List.of("A", "a"),
            null, null, 1, null, null));
    }

    @Test
    public void moveUpDownAndClampWithoutIntegerOverflow()
    {
        List<String> names = List.of("A", "B", "C", "D");
        assertEquals(List.of("A", "D", "B", "C"), order(names, "move", null, "D", "up", 2, null, null));
        assertEquals(List.of("B", "C", "A", "D"), order(names, "move", null, "A", "down", 2, null, null));
        assertEquals(List.of("D", "A", "B", "C"), order(names, "move", null, "D", "up", Integer.MAX_VALUE, null, null));
        assertEquals(List.of("A", "C", "D", "B"), order(names, "move", null, "B", "down", Integer.MAX_VALUE, null, null));
        assertEquals(names, order(names, "move", null, "A", "up", 1, null, null));
    }

    @Test
    public void moveBeforeAfterWorksInBothDirections()
    {
        List<String> names = List.of("A", "B", "C", "D");
        assertEquals(List.of("D", "A", "B", "C"), order(names, "move", null, "D", null, 1, "A", null));
        assertEquals(List.of("B", "C", "A", "D"), order(names, "move", null, "A", null, 1, "D", null));
        assertEquals(List.of("B", "C", "D", "A"), order(names, "move", null, "A", null, 1, null, "D"));
        assertEquals(List.of("A", "D", "B", "C"), order(names, "move", null, "D", null, 1, null, "A"));
    }

    @Test
    public void moveRejectsMissingObjectMissingSiblingAndSelf()
    {
        assertThrows(IllegalArgumentException.class, () -> order(List.of("A"), "move", null, "Missing", "up", 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> order(List.of("A"), "move", null, "A", null, 1, "Missing", null));
        assertThrows(IllegalArgumentException.class, () -> order(List.of("A"), "move", null, "A", null, 1, null, "a"));
    }

    @Test
    public void movedReportsEveryChangedOneBasedPosition()
    {
        assertEquals(List.of(java.util.Map.of("name", "C", "from", 3, "to", 1),
            java.util.Map.of("name", "A", "from", 1, "to", 2),
            java.util.Map.of("name", "B", "from", 2, "to", 3)),
            MetadataOrdering.moved(List.of("A", "B", "C"), List.of("C", "A", "B")));
    }

    @Test
    public void bilingualLevelsResolveSameCollectionWithoutUsingSynonym()
    {
        Configuration config = factory.createConfiguration();
        Catalog catalog = factory.createCatalog();
        catalog.setName("\u0422\u043e\u0432\u0430\u0440\u044b");
        catalog.getSynonym().put("ru", "DisplayName");
        config.getCatalogs().add(catalog);
        var english = MetadataOrdering.resolve(config, "Catalog.\u0422\u043e\u0432\u0430\u0440\u044b.Attributes");
        var russian = MetadataOrdering.resolve(config,
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.\u0422\u043e\u0432\u0430\u0440\u044b.\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442\u044b");
        assertSame(catalog, english.owner());
        assertEquals(english.collections(), russian.collections());
        assertTrue(english.singleCollection());
        assertThrows(IllegalArgumentException.class, () -> MetadataOrdering.resolve(config, "Catalog.DisplayName.Attributes"));
        assertFalse(MetadataOrdering.resolve(config, "Catalog." + catalog.getName()).singleCollection());
    }

    @Test
    public void configurationAndRussianTypeCollectionResolve()
    {
        Configuration config = factory.createConfiguration();
        assertSame(config, MetadataOrdering.resolve(config, "Configuration").owner());
        assertEquals(List.of(MdClassPackage.Literals.CONFIGURATION__CATALOGS),
            MetadataOrdering.resolve(config, "Configuration.Catalogs").collections());
        assertEquals(List.of(MdClassPackage.Literals.CONFIGURATION__CATALOGS), MetadataOrdering.resolve(config,
            "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f.\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a\u0438").collections());
    }

    @Test
    public void unknownMalformedOrScalarCollectionIsRefused()
    {
        Configuration config = factory.createConfiguration();
        for (String fqn : List.of("Configuration.Name", "Configuration.Missing", "Configuration.", "Configuration.Catalogs.More"))
        {
            assertThrows(fqn, IllegalArgumentException.class, () -> MetadataOrdering.resolve(config, fqn));
        }
    }

    @Test
    public void recursionFollowsOwnershipButNeverReferenceTargets()
    {
        Configuration config = factory.createConfiguration();
        Catalog catalog = factory.createCatalog();
        assertTrue(MetadataOrdering.ownsChildren(config, MdClassPackage.Literals.CONFIGURATION__CATALOGS));
        assertTrue(MetadataOrdering.ownsChildren(catalog, MdClassPackage.Literals.CATALOG__TABULAR_SECTIONS));
        assertFalse(MetadataOrdering.ownsChildren(config, MdClassPackage.Literals.CONFIGURATION__DEFAULT_ROLES));
        assertFalse(MetadataOrdering.ownsChildren(factory.createSubsystem(), MdClassPackage.Literals.SUBSYSTEM__CONTENT));
        assertTrue(MetadataOrdering.ownsChildren(factory.createSubsystem(), MdClassPackage.Literals.SUBSYSTEM__SUBSYSTEMS));
    }

    @Test
    public void criticalWarningsAreExplicit()
    {
        assertTrue(MetadataOrdering.warning(factory.createInformationRegister(),
            MdClassPackage.Literals.INFORMATION_REGISTER__DIMENSIONS).contains("indexes"));
        assertTrue(MetadataOrdering.warning(factory.createEnum(), MdClassPackage.Literals.ENUM__ENUM_VALUES).contains("ORDER BY"));
        assertTrue(MetadataOrdering.warning(factory.createSubsystem(), MdClassPackage.Literals.SUBSYSTEM__CONTENT).contains("command interface"));
        assertTrue(MetadataOrdering.warning(factory.createOperation(), MdClassPackage.Literals.OPERATION__PARAMETERS).contains("handler signatures"));
    }

    @Test
    public void insertBoundsAndIdentityPreservingMove()
    {
        MetadataOrdering.validateInsertPosition(null, 0);
        MetadataOrdering.validateInsertPosition(1, 0);
        MetadataOrdering.validateInsertPosition(3, 2);
        for (int invalid : new int[] {-1, 0, 4, Integer.MAX_VALUE})
        {
            assertThrows(IllegalArgumentException.class, () -> MetadataOrdering.validateInsertPosition(invalid, 2));
        }
        Catalog owner = factory.createCatalog();
        CatalogAttribute a = factory.createCatalogAttribute();
        CatalogAttribute b = factory.createCatalogAttribute();
        owner.getAttributes().add(a);
        owner.getAttributes().add(b);
        EReference ref = MdClassPackage.Literals.CATALOG__ATTRIBUTES;
        MetadataOrdering.positionCreated(owner, ref, b, 1);
        assertEquals(List.of(b, a), owner.getAttributes());
        assertSame(owner, b.eContainer());
        MetadataOrdering.positionCreated(owner, ref, a, null);
        assertEquals(List.of(b, a), owner.getAttributes());
    }

    @Test
    public void insertBoundsRenderTransportWholeNumbersWithTheActualCollectionRange()
    {
        for (String text : List.of("0.0", "-1.0", "8.0"))
        {
            Integer position = com.ditrix.edt.mcp.server.protocol.JsonUtils.parseIntegralInt(text);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MetadataOrdering.validateInsertPosition(position, 6));
            assertEquals("insertPosition " + position + " is outside 1..7; choose a 1-based index "
                + "in the destination collection or omit insertPosition to append.", error.getMessage());
            assertFalse(error.getMessage().contains(text));
        }
    }

    @Test
    public void createDestinationUsesSharedLevelResolver()
    {
        Configuration config = factory.createConfiguration();
        Catalog owner = factory.createCatalog();
        owner.setName("X");
        config.getCatalogs().add(owner);
        assertEquals(List.of(MdClassPackage.Literals.CONFIGURATION__CATALOGS),
            MetadataOrdering.destination(config, "Catalog.New").collections());
        assertEquals(List.of(MdClassPackage.Literals.CATALOG__ATTRIBUTES),
            MetadataOrdering.destination(config, "Catalog.X.Attribute.New").collections());
        assertEquals(List.of(MdClassPackage.Literals.CATALOG__FORMS),
            MetadataOrdering.destination(config, "Catalog.X.Form.New").collections());
    }
}
