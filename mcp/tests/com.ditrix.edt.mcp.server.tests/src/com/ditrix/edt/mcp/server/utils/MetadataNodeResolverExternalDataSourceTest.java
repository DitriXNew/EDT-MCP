/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.util.List;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/** The four-tool grammar, canonical labels and the transaction walk (#448). */
public class MetadataNodeResolverExternalDataSourceTest
{
    private final ExternalDataSourceFixtures f = new ExternalDataSourceFixtures();

    @Test public void tableAndCubeAreReferencedChildren()
    {
        var table = MetadataNodeResolver.resolveExisting(f.config, TABLE);
        assertNotNull(table);
        assertSame(f.table, table.object);
        assertSame(f.eds, table.owner);
        assertEquals("tables", table.feature.getName());
        assertFalse(table.topLevel);
        assertFalse(table.feature.isContainment());
        assertSame(f.cube, MetadataNodeResolver.resolveExisting(f.config, CUBE).object);
    }

    @Test public void cubeAndFunctionTranslateToTheirXcoreRussianSpelling()
    {
        // MdClass.xcore code_ru of Cube and Function; the canonical Russian is what a translated
        // marker location shows, so it must be the platform's capitalized spelling.
        assertEquals(RU_CUBE, MetadataTypeUtils.resolveNestedKind("Cubes").getRussian());
        assertEquals(RU_FUNCTION, MetadataTypeUtils.resolveNestedKind("function").getRussian());
        assertEquals("Cube", MetadataTypeUtils.resolveNestedKind(RU_CUBE).getEnglish());
        assertEquals("Function", MetadataTypeUtils.resolveNestedKind(RU_FUNCTION).getEnglish());
    }

    @Test public void russianTokensResolveAtEveryLevel()
    {
        var node = MetadataNodeResolver.resolveExisting(f.config,
            RU_EDS + ".Eds." + RU_TABLE + ".T." + RU_FIELD + ".F");
        assertNotNull(node);
        assertSame(f.field, node.object);
        assertSame(f.table, node.owner);
        assertEquals("tableFields", node.feature.getName());
        assertSame(f.function, MetadataNodeResolver.resolveExisting(f.config,
            RU_EDS + ".Eds." + RU_FUNCTION + ".Fn").object);
        assertSame(f.cube, MetadataNodeResolver.resolveExisting(f.config,
            RU_EDS + ".Eds." + RU_CUBE + ".C").object);
    }

    @Test public void createTargetsKeepTheWalkRootAndLeafType()
    {
        var table = MetadataNodeResolver.resolveForCreate(f.config, EDS + ".Table.New");
        assertNotNull(table);
        assertSame(MdClassPackage.Literals.TABLE, table.elementType);
        assertFalse(table.feature.isContainment());
        var field = MetadataNodeResolver.resolveForCreate(f.config, TABLE + ".Field.New");
        assertNotNull(field);
        assertSame(f.table, field.owner);
        assertSame(f.eds, field.topObject);
        assertSame(MdClassPackage.Literals.FIELD, field.elementType);
        var function = MetadataNodeResolver.resolveForCreate(f.config, EDS + ".Function.New");
        assertNotNull(function);
        assertSame(MdClassPackage.Literals.FUNCTION, function.elementType);
        assertSame(f.eds, function.topObject);
    }

    @Test public void publishedChildKindsUseTheMetamodelCode()
    {
        assertEquals(List.of("Table", "Cube", "Function"), MetadataNodeResolver.childKindsFor(f.eds));
        assertEquals(List.of("Command", "Template", "Field"), MetadataNodeResolver.childKindsFor(f.table));
        assertEquals("Field", MetadataNodeResolver.kindTokenForFeature("tableFields"));
        assertEquals("tableFields", MetadataNodeResolver.featureNameForKind("Field"));
    }

    @Test public void addressesFollowBackReferencesAndRoundTrip()
    {
        assertEquals(TABLE, MetadataNodeResolver.resolvableAddressOf(f.table));
        assertEquals(FIELD, MetadataNodeResolver.resolvableAddressOf(f.field));
        assertEquals(FIELD, MetadataNodeResolver.addressOf(f.field));
        assertEquals(CUBE, MetadataNodeResolver.resolvableAddressOf(f.cube));
        assertSame(f.field, MetadataNodeResolver.resolveExisting(f.config,
            MetadataNodeResolver.resolvableAddressOf(f.field)).object);
        f.table.setParentDataSource(null);
        assertNull(MetadataNodeResolver.resolvableAddressOf(f.table));
    }

    @Test public void transactionWalkCrossesTheReferenceList()
    {
        assertSame(f.table, MetadataNodeResolver.resolveOwnerInTx(f.eds, FIELD.split("\\.")));
        assertNull(MetadataNodeResolver.resolveOwnerInTx(f.eds,
            (EDS + ".Table.Missing.Field.F").split("\\.")));
    }

    @Test public void aliasesArePublishedByTheSharedCatalogue()
    {
        for (String kind : List.of("Table", "Field", "Function", "Cube"))
        {
            for (String alias : MetadataTypeUtils.nestedKindAliases(kind))
            {
                assertNotNull(kind + ": " + alias, MetadataNodeResolver.featureNameForKind(alias));
            }
        }
        assertEquals("Function", MetadataTypeUtils.resolveNestedKind(RU_FUNCTION).getEnglish());
        assertEquals("Cube", MetadataTypeUtils.resolveNestedKind("\u041a\u0443\u0431\u044b").getEnglish());
    }

    @Test public void malformedEdsSegmentsNeverResolveOrCreateANeighbor()
    {
        for (String fqn : List.of(TABLE + ".", TABLE + "..", EDS + ".Table. T",
            EDS + ".Table.T ", EDS + "..T", EDS + ".Table.", TABLE + ".Field.New."))
        {
            assertNull(fqn, MetadataNodeResolver.resolveExisting(f.config, fqn));
            assertNull(fqn, MetadataNodeResolver.resolveForCreate(f.config, fqn));
        }
    }

    @Test public void formOnlyAndCubeDimensionPathsStayOutsideTheGrammar()
    {
        assertNotNull(MetadataNodeResolver.resolveExisting(f.config, CUBE));
        assertNull(MetadataNodeResolver.resolveForCreate(f.config, CUBE + ".Dimension.D"));
        assertNull(MetadataNodeResolver.resolveForCreate(f.config, CUBE + ".Resource.R"));
        assertNull(MetadataNodeResolver.resolveForCreate(f.config, CUBE + ".DimensionTable.D"));
        assertNull(MetadataNodeResolver.resolveForCreate(f.config, TABLE + ".Form.F"));
        assertNull(MetadataNodeResolver.resolveForCreate(f.config, "Catalog.P.Field.F"));
        assertFalse(MetadataNodeResolver.childKindsFor(f.cube).contains("Dimension"));
        var dimension = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createDimension();
        dimension.setName("D");
        f.cube.getDimensions().add(dimension);
        assertNull(MetadataNodeResolver.resolvableAddressOf(dimension));
    }
}
