/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

public class GetMetadataDetailsExternalDataSourceTest
{
    @Test public void tableAndCubeRenderTheirOwnDetailsInEitherLanguage()
    {
        for (String fqn : List.of(TABLE, RU_EDS + ".Eds." + RU_TABLE + ".T", CUBE,
            RU_EDS + ".Eds." + RU_CUBE + ".C"))
        {
            var failures = new ArrayList<String[]>();
            String body = render(fqn, false, failures);
            assertTrue(fqn + ": " + failures.size(), failures.isEmpty());
            assertTrue(body, body.contains(fqn.endsWith(".T") ? "## Table: T" : "## Cube: C"));
            assertFalse(body, body.contains("## ExternalDataSource: Eds"));
        }
    }

    @Test public void fieldRefusalNamesItsImmediateTableOwnerInEitherLanguage()
    {
        for (String fqn : List.of(FIELD, RU_EDS + ".Eds." + RU_TABLE + ".T." + RU_FIELD + ".F"))
        {
            var failures = new ArrayList<String[]>();
            assertEquals("", render(fqn, false, failures));
            assertEquals(1, failures.size());
            assertEquals("This addresses a member of '" + TABLE
                + "'; read its properties with assignable: true. The owner's details render from '" + TABLE + "'.",
                failures.get(0)[1]);
        }
    }

    @Test public void tabularSectionAttributeRefusalNamesItsTopObjectNotItsSection()
    {
        // The section itself is refused as a member of the catalog, so naming it would loop the caller.
        var f = new ExternalDataSourceFixtures();
        var goods = named(com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCatalog(), "Goods");
        var lines = named(com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCatalogTabularSection(),
            "Lines");
        lines.getAttributes().add(named(
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createTabularSectionAttribute(), "Quantity"));
        goods.getTabularSections().add(lines);
        f.config.getCatalogs().add(goods);
        for (String fqn : List.of("Catalog.Goods.TabularSection.Lines.Attribute.Quantity",
            RU_CATALOG + ".Goods." + RU_TABULAR_SECTION + ".Lines." + RU_ATTRIBUTE + ".Quantity"))
        {
            var failures = new ArrayList<String[]>();
            assertEquals("", render(f, fqn, false, failures));
            assertEquals(fqn, 1, failures.size());
            assertEquals("This addresses a member of 'Catalog.Goods'; read its properties with assignable: true. "
                + "The owner's details render from 'Catalog.Goods'.", failures.get(0)[1]);
        }
        assertSame(goods, GetMetadataDetailsTool.renderableOwner(lines));
        assertSame(f.table, GetMetadataDetailsTool.renderableOwner(f.table));
        assertSame(f.eds, GetMetadataDetailsTool.renderableOwner(f.eds));
    }

    @Test public void fieldsAndFunctionsHaveTheAssignableView()
    {
        for (String fqn : List.of(FIELD, FUNCTION))
        {
            var failures = new ArrayList<String[]>();
            String body = render(fqn, true, failures);
            assertTrue(fqn, failures.isEmpty());
            assertTrue(body, body.contains("## Assignable properties:"));
            assertTrue(body, body.contains(fqn.equals(FIELD) ? "allowNull" : "returnValue"));
        }
    }

    private static final String RU_CATALOG = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a";
    private static final String RU_ATTRIBUTE = "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442";
    private static final String RU_TABULAR_SECTION =
        "\u0422\u0430\u0431\u043b\u0438\u0447\u043d\u0430\u044f\u0427\u0430\u0441\u0442\u044c";

    private static String render(String fqn, boolean assignable, List<String[]> failures)
    {
        return render(new ExternalDataSourceFixtures(), fqn, assignable, failures);
    }

    private static String render(ExternalDataSourceFixtures f, String fqn, boolean assignable,
        List<String[]> failures)
    {
        var context = new GetMetadataDetailsTool.RenderContext(null, f.scope(), null, "en", true, assignable, false, 0);
        var body = new StringBuilder();
        VendorSupportGuard.setServiceForTests(() -> null);
        try
        {
            new GetMetadataDetailsTool().processFqn(fqn, body, failures, context);
        }
        finally
        {
            VendorSupportGuard.setServiceForTests(null);
        }
        return body.toString();
    }
}
