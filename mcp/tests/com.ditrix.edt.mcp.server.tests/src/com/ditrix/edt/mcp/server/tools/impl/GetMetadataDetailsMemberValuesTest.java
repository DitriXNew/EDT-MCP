/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataMemberValues;
import com.google.gson.JsonParser;

public class GetMetadataDetailsMemberValuesTest
{
    @Test public void currentValuesUseTheWriterGrammarInBothNameLanguages()
    {
        for (String name : java.util.List.of("Attribute", "Атрибут"))
        {
            var attribute = MdClassFactory.eINSTANCE.createCatalogAttribute(); attribute.setName(name);
            for (String property : java.util.List.of("fillValue", "choiceParameters", "choiceParameterLinks"))
            {
                String raw = property.equals("fillValue") ? "{kind:'string',value:'Текст'}"
                    : property.equals("choiceParameters") ? "[{name:'Отбор.Параметр',value:{kind:'boolean',value:true}}]" : "[]";
                MetadataMemberValues.prepare(null, attribute, property, JsonParser.parseString(raw))
                    .apply(attribute, attribute.eClass().getEStructuralFeature(property), null);
            }
            String text = GetMetadataDetailsTool.formatAssignable("Catalog.Товары.Attribute." + name, attribute);
            assertTrue(text, text.contains("{\"kind\":\"string\",\"value\":\"Текст\"}"));
            assertTrue(text, text.contains("[{\"name\":\"Отбор.Параметр\",\"value\":{\"kind\":\"boolean\",\"value\":true}}]"));
            assertTrue(text, text.contains("| choiceParameterLinks | MEMBER_VALUES | [] |"));
        }
    }
}
