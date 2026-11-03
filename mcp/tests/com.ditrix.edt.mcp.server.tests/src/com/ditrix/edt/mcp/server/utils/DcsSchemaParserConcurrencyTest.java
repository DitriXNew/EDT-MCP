/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com.google.gson.JsonParser;

/** Schema writers must remain unlocked without sharing a paused guard's parser errors. */
public class DcsSchemaParserConcurrencyTest
{
    @Test
    public void testDeclaredDefaultCannotClearGuardParserError() throws Exception
    {
        schemaRace("{parameters:[{name:'P',values:[{kind:'string',value:'valid'}]}]}", false);
    }

    @Test
    public void testAvailableValueCannotClearGuardParserError() throws Exception
    {
        schemaRace("{dataSets:[{name:'DS',type:'query',query:'SELECT 1',fields:[{dataPath:'Code',"
            + "availableValues:[{value:{kind:'string',value:'valid'}}]}]}]}", false);
    }

    @Test
    public void testInputParameterCannotClearGuardParserError() throws Exception
    {
        schemaRace("{dataSets:[{name:'DS',type:'query',query:'SELECT 1',fields:[{dataPath:'Code',"
            + "inputParameters:{items:[{parameter:{kind:'parameter',value:'P'},"
            + "values:[{kind:'string',value:'valid'}]}]}}]}]}", false);
    }

    @Test
    public void testRejectedSchemaValueCannotReplaceGuardParserError() throws Exception
    {
        schemaRace("{parameters:[{name:'P',values:[{kind:'field',value:''}]}]}", true);
    }

    private static void schemaRace(String body, boolean rejected) throws Exception
    {
        DcsParserConcurrencyTestSupport.race(false, () -> {
            var schema = DcsStoredXmlTestSupport.schema();
            var result = DcsWriter.apply(schema, JsonParser.parseString(body).getAsJsonObject(), null);
            assertEquals(result.error, rejected, result.hasError());
            assertFalse(Thread.holdsLock(DcsSettingsWriter.class));
            return null;
        });
    }
}
