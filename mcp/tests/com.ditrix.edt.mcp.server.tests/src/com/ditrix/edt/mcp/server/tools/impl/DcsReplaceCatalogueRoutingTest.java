/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DcsFactory;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.*;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/** Verifies root-specific catalogue wiring through the tool's actual replacement path. */
public class DcsReplaceCatalogueRoutingTest
{
    @Test
    public void testFormAndDynamicListUseTheirOwnCatalogueKind()
    {
        for (TargetKind root : TargetKind.values())
        {
            List<Enum<?>> loaded = new ArrayList<>();
            DcsWriterCatalogues context = DcsTool.replaceCatalogues(root, Version.V8_3_27, null,
                (kind, version, language) ->
                {
                    loaded.add(kind);
                    assertEquals(Version.V8_3_27, version);
                    assertEquals("en", language);
                    DcsAvailableParameterCollection parameters = new DcsAvailableParameterCollection();
                    parameters.addItem().init(new String[] {"Known", "Alias"}, null, null, "Known",
                        McoreFactory.eINSTANCE.createStringValue(), false, null, true, false, null,
                        version, null);
                    return parameters;
                });
            var holder = DcsFactory.eINSTANCE.createDataCompositionAppearance();
            var item = DcsFactory.eINSTANCE.createDataCompositionParameterValue();
            var parameter = DcsFactory.eINSTANCE.createDataCompositionParameter();
            parameter.setValue("Known");
            item.setParameter(parameter);
            item.getValues().add(McoreFactory.eINSTANCE.createStringValue());
            holder.getItems().add(item);
            assertNull(DcsTool.replaceRefusal(holder, null, "schema",
                DcsAddress.parse("Report.Sales").address(), context));
            AppearanceCatalogue expected = root == TargetKind.FORM ? AppearanceCatalogue.FORM
                : root == TargetKind.DYNAMIC_LIST ? AppearanceCatalogue.DYNAMIC_LIST : AppearanceCatalogue.SCHEMA;
            assertEquals(List.of(expected), loaded);
            parameter.setValue("Unknown");
            String refused = DcsTool.replaceRefusal(holder, null, "schema",
                DcsAddress.parse("Report.Sales").address(), context);
            assertNotNull(refused);
            assertTrue(refused, refused.contains("'Unknown' is not in the platform catalogue for 8.3.27"));
            // Only a DataCompositionSchema root has the lossless XML channel to offer.
            assertEquals(refused, expected == AppearanceCatalogue.SCHEMA,
                refused.contains("lossless XML channel"));
        }
    }
}
