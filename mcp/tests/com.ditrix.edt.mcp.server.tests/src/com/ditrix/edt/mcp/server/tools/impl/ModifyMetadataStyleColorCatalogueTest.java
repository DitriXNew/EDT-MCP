/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.AppearanceValueBuilder;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.PlatformColorTestRuntime;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The actual STYLE_VALUE prepare dispatch carries the project's platform version. */
public class ModifyMetadataStyleColorCatalogueTest
{
    @Test
    public void testStyleItemsResolveEveryPlatformColorSourceThroughPrepare() throws Exception
    {
        try (var ignored = new PlatformColorTestRuntime("Web.Red", "Windows.WindowText", "Style.FieldBackColor"))
        {
            String[][] cases = {{"web", "Red", "Web.Red"}, {"windows", "WindowText", "Windows.WindowText"},
                {"style", "FieldBackColor", "Style.FieldBackColor"}};
            for (String[] sample : cases)
            {
                var item = MdClassFactory.eINSTANCE.createStyleItem();
                item.setName("NewStyleColor");
                var config = MdClassFactory.eINSTANCE.createConfiguration();
                config.getStyleItems().add(item);
                Class<?> contextType = Class.forName(ModifyMetadataTool.class.getName() + "$PrepareContext");
                var constructor = contextType.getDeclaredConstructor(IProject.class, MetadataScope.class,
                    Configuration.class, Version.class, List.class);
                constructor.setAccessible(true);
                Object context = constructor.newInstance(null, null, config, Version.V8_3_27, null);
                var prepare = ModifyMetadataTool.class.getDeclaredMethod("prepare", contextType, EObject.class,
                    EObject.class, JsonObject.class, List.class, MdNameNormalizer.Report.class, boolean.class);
                prepare.setAccessible(true);
                var body = JsonParser.parseString("{\"name\":\"value\",\"value\":{\"color\":{\"" + sample[0]
                    + "\":\"" + sample[1] + "\"}}}").getAsJsonObject();
                List<ModifyMetadataTool.PreparedChange> changes = new ArrayList<>();
                String error = (String)prepare.invoke(new ModifyMetadataTool(), context, item, null, body,
                    changes, null, false);
                assertNull(sample[2], error);
                assertEquals(1, changes.size());
                changes.get(0).applyTo(item, null);
                var ref = (ColorRef)((ColorValue)item.getValue()).getValue();
                assertEquals(sample[2], AppearanceValueBuilder.colorReferenceName(ref));
            }
        }
    }
}
