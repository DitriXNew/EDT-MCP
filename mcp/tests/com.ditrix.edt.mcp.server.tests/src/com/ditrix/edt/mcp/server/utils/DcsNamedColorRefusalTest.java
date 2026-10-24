/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.google.gson.JsonParser;

/** The DCS retry text must describe the object form its typed color writer accepts. */
public class DcsNamedColorRefusalTest
{
    private static final String FORMS = "Use {color:{red:0-255, green:0-255, blue:0-255}}, "
        + "{color:'auto'}, or a named color {color:{<source>:'<Name>'}} with source style "
        + "(a configuration style item or a platform style color), palette, web or windows - "
        + "e.g. {color:{web:'Red'}} or {color:{style:'FormBackColor'}}.";

    @Test
    public void testEmptyCandidateRefusalOnTheDcsTypedPathIsPinnedVerbatim()
    {
        var result = DcsSettingsWriter.buildTypedParameterValue(
            JsonParser.parseString("{color:{web:'Missing'}}"), McoreFactory.eINSTANCE.createColorValue(),
            null, "value");
        assertEquals("Typed parameter value at 'value' is invalid: Unknown Web color 'Missing'. "
            + "The platform catalogue is unavailable for this project's platform version, "
            + "so platform names cannot be checked. " + FORMS, result.error);
        assertFalse(result.error.contains("{color:'<Source>.<Name>'}"));
    }

    @Test
    public void testStyleItemEmptyCandidateRefusalAlsoUsesOnlyTheObjectForm()
    {
        var result = StyleValueBuilder.build(JsonParser.parseString("{color:{windows:'Missing'}}"),
            StyleValueBuilder.forConfiguration(null));
        assertEquals("Unknown Windows color 'Missing'. The platform catalogue is unavailable for "
            + "this project's platform version, so platform names cannot be checked. " + FORMS, result.error);
    }

    @Test
    public void testFormAppearanceStillOffersItsSupportedStringForm()
    {
        var result = AppearanceValueBuilder.buildColor(JsonParser.parseString("{color:{web:'Missing'}}"),
            null, null);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("{color:'<Source>.<Name>'}"));
    }
}
