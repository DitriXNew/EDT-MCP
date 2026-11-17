// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;
import com.google.gson.JsonParser;

/** Exact integer properties reject fractional values before preparing a mutation. */
public class MetadataPropertyApplierTest
{
    @Test public void integerPropertiesRetainExactValuesAndBounds()
    {
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), MetadataPropertyApplier.parseInteger("-2147483648.0"));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), MetadataPropertyApplier.parseInteger("2147483647.0"));
        assertEquals(Integer.valueOf(100), MetadataPropertyApplier.parseInteger("1e2"));
        assertEquals(Integer.valueOf(0), MetadataPropertyApplier.parseInteger("0.0"));
    }

    @Test public void invalidIntegersKeepTheModifyMetadataErrorAndPrepareNothing()
    {
        for (String raw : new String[] {null, "", "NaN", "Infinity", "1.5", "1.0000000000000001",
            "2147483647.00000001", "2147483648", "-2147483649"})
        {
            assertNull(raw, MetadataPropertyApplier.parseInteger(raw));
            var changes = new ArrayList<MetadataPropertyApplier.PreparedChange>();
            String result = MetadataPropertyApplier.prepareInteger("codeLength", raw, null, changes);
            assertEquals("'" + raw + "' is not a valid integer for 'codeLength'.",
                JsonParser.parseString(result).getAsJsonObject().get("error").getAsString());
            assertTrue(changes.isEmpty());
        }
    }
}
