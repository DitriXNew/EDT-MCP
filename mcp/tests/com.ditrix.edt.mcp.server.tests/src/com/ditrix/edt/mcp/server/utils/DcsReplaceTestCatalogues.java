/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DcsFactory;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.path.DcsPathException;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;

/** Explicit catalogue sources for guard tests; never substitutes an empty catalogue. */
public final class DcsReplaceTestCatalogues
{
    private DcsReplaceTestCatalogues() { }

    public static DcsWriterCatalogues schema()
    {
        return schema(Version.V8_3_27);
    }

    public static DcsWriterCatalogues schema(Version version)
    {
        return new DcsWriterCatalogues(version, AppearanceCatalogue.SCHEMA, "en",
            DcsReplaceTestCatalogues::load);
    }

    public static DcsAvailableParameterCollection load(Enum<?> kind, Version version, String language)
        throws DcsPathException
    {
        try (DcsCatalogueTestRuntime.Scope ignored = DcsCatalogueTestRuntime.prepareCatalogues(version))
        {
            return kind instanceof OutputParameterCatalogue
                ? DcsSettingsWriter.outputParameters((OutputParameterCatalogue)kind, version, language)
                : DcsSettingsWriter.appearanceParameters((AppearanceCatalogue)kind, version, language);
        }
    }

    static DataCompositionParameter parameter(String name)
    {
        DataCompositionParameter result = DcsFactory.eINSTANCE.createDataCompositionParameter();
        result.setValue(name);
        return result;
    }

    static DcsAvailableParameterCollection available(String name, String alias, Value declared)
    {
        DcsAvailableParameterCollection result = new DcsAvailableParameterCollection();
        try
        {
            result.addItem().init(new String[] {name, alias}, null, null, name, declared, false,
                null, true, false, null, Version.V8_3_27, null);
        }
        catch (DcsPathException e)
        {
            throw new AssertionError(e);
        }
        return result;
    }
}
