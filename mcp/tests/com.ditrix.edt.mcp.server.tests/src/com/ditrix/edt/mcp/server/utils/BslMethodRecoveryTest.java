/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.BslFactory;

public class BslMethodRecoveryTest
{
    @Test
    public void reportsRecoveredLocationsWhenSemanticAnalysisNeedsAnAst()
    {
        var module = BslFactory.eINSTANCE.createModule();
        var method = BslFactory.eINSTANCE.createProcedure();
        method.setName("Before");
        module.getMethods().add(method);
        var text = BslMethodRecovery.scan(List.of("Procedure Before()", "EndProcedure",
            "#Область Внешняя", "Функция Пропущенная() Экспорт", "Возврат 1;", "КонецФункции",
            "#КонецОбласти", "Procedure Tail()", "EndProcedure"));

        for (String name : List.of("пРОПУЩЕННАЯ", "tail"))
        {
            String result = BslMethodRecovery.missingMethodForAnalysis(module, "Module.bsl", name, text);
            assertTrue(result, result.contains("Method '" + name + "' in Module.bsl was found in saved source at lines "
                + (name.equals("tail") ? "8-9" : "4-6")));
            assertTrue(result, result.contains("EDT's parse of the module is incomplete"));
            assertTrue(result, result.contains("read_method_source"));
            assertFalse(result, result.contains("not found"));
        }

        String missing = BslMethodRecovery.missingMethodForAnalysis(module, "Module.bsl", "Missing", text);
        assertTrue(missing, missing.contains("Method 'Missing' not found in Module.bsl"));
        assertTrue(missing, missing.contains("**Available methods** (3):\n\n- Before\n- Пропущенная\n- Tail\n"));
    }
}
