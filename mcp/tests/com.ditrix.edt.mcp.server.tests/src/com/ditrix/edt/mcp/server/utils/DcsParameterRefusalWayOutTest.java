/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;

/**
 * #682: a parameter-value refusal names only the way out the scanned root actually has. The
 * lossless XML channel replaces a bare DataCompositionSchema root, so a form's conditional
 * appearance and a dynamic list must not be sent there.
 */
public class DcsParameterRefusalWayOutTest
{
    private static final String ROOT = "Report.Sales";

    @Test
    public void testOnlyASchemaRootRefusalOffersTheXmlChannel()
    {
        for (AppearanceCatalogue kind : AppearanceCatalogue.values())
        {
            DataCompositionAppearance holder = appearance("Unknown");
            String error = DcsMutationGuard.replaceError(holder, address(ROOT), context(kind));
            assertNotNull(kind.name(), error);
            assertTrue(error, error.contains("'Unknown' is not in the platform catalogue for 8.3.27"));
            assertTrue(error, error.contains("Use action='update' on those items"));
            assertTrue(error, error.contains("DCS designer"));
            if (kind == AppearanceCatalogue.SCHEMA)
            {
                assertTrue(error, error.contains("lossless XML channel (action='replace', "
                    + "type='schema', body={xml:...} on the bare schema root)"));
            }
            else
            {
                assertFalse(kind.name() + " has no XML channel: " + error, error.contains("XML"));
            }
        }
    }

    @Test
    public void testAnItemWithoutAParameterNameGetsItsOwnReason()
    {
        DataCompositionAppearance holder = appearance(null);
        String error = DcsMutationGuard.replaceError(holder, address(ROOT),
            context(AppearanceCatalogue.SCHEMA));
        assertNotNull(error);
        assertTrue(error, error.contains("(the item has no parameter name; the typed writer needs one)"));
        assertFalse(error, error.contains("'null'"));
    }

    /** The premise of the form call-site e2e: 'Visible' exists in the form catalogue only. */
    @Test
    public void testVisibleIsAFormOnlyAppearanceKeyInThePlatformCatalogues() throws Exception
    {
        assertNotNull(DcsReplaceTestCatalogues.load(AppearanceCatalogue.FORM, Version.V8_3_27, "en")
            .findItem("Visible"));
        assertNull(DcsReplaceTestCatalogues.load(AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en")
            .findItem("Visible"));
        DataCompositionAppearance holder = appearance("Visible");
        BooleanValue visible = McoreFactory.eINSTANCE.createBooleanValue();
        ((SettingsParameterValue)holder.getItems().get(0)).getValues().set(0, visible);
        assertNull(DcsMutationGuard.replaceError(holder, address(ROOT), real(AppearanceCatalogue.FORM)));
        String schema = DcsMutationGuard.replaceError(holder, address(ROOT), real(AppearanceCatalogue.SCHEMA));
        assertNotNull(schema);
        assertTrue(schema, schema.contains("'Visible' is not in the platform catalogue"));
    }

    private static DataCompositionAppearance appearance(String name)
    {
        DataCompositionAppearance holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionAppearance();
        SettingsParameterValue item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        if (name != null) item.setParameter(DcsReplaceTestCatalogues.parameter(name));
        com._1c.g5.v8.dt.mcore.StringValue text = McoreFactory.eINSTANCE.createStringValue();
        text.setValue("Text");
        item.getValues().add(text);
        holder.getItems().add(item);
        return holder;
    }

    private static DcsWriterCatalogues context(AppearanceCatalogue kind)
    {
        return new DcsWriterCatalogues(Version.V8_3_27, kind, "en", (catalogue, version, language) ->
            DcsReplaceTestCatalogues.available("Known", "Alias", McoreFactory.eINSTANCE.createStringValue()));
    }

    private static DcsWriterCatalogues real(AppearanceCatalogue kind)
    {
        return new DcsWriterCatalogues(Version.V8_3_27, kind, "en", DcsReplaceTestCatalogues::load);
    }

    private static DcsAddress address(String raw)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(raw);
        assertTrue(raw, parsed.isSuccess());
        return parsed.address();
    }
}
