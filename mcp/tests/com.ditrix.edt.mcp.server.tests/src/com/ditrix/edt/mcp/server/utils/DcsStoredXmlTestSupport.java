/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;

/** EDT XML fixtures shared by the round-six regression tests. */
final class DcsStoredXmlTestSupport
{
    static final Version VERSION = Version.V8_3_27;
    static final DcsFactory SETTINGS = DcsFactory.eINSTANCE;

    private DcsStoredXmlTestSupport() { }

    static DataCompositionSchema schema()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(SETTINGS.createDataCompositionSettings());
        return schema;
    }

    static DataCompositionSchema operand(boolean filter, Value value)
    {
        DataCompositionSchema schema = schema();
        if (filter)
        {
            var group = SETTINGS.createDataCompositionGroup();
            schema.getDefaultSettings().getItems().add(group);
            group.setFilter(SETTINGS.createDataCompositionFilter());
            var item = SETTINGS.createDataCompositionFilterItem();
            var left = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
            left.setValue("Amount");
            item.setLeft(left);
            item.getRight().add(value);
            group.getFilter().getItems().add(item);
        }
        else
        {
            var holder = SETTINGS.createDataCompositionDataParameterValues();
            var item = SETTINGS.createSettingsParameterValue();
            item.setParameter(DcsReplaceTestCatalogues.parameter("P"));
            item.getValues().add(value);
            holder.getItems().add(item);
            schema.getDefaultSettings().setDataParameters(holder);
        }
        return schema;
    }

    static List<Value> operands(DataCompositionSchema schema, boolean filter)
    {
        if (!filter) return schema.getDefaultSettings().getDataParameters().getItems().get(0).getValues();
        var group = (DataCompositionGroup)schema.getDefaultSettings().getItems().get(0);
        return ((DataCompositionFilterItem)group.getFilter().getItems().get(0)).getRight();
    }

    static String guard(DataCompositionSchema schema)
    {
        return DcsMutationGuard.replaceError(schema, DcsAddress.parse("Report.Sales").address(),
            DcsReplaceTestCatalogues.schema(VERSION));
    }

    static String serialize(DataCompositionSchema schema) throws Exception
    {
        var serializer = new DcsV8Serializer((IDtProject)null, VERSION, (IResourceLookup)null);
        var output = new ByteArrayOutputStream();
        serializer.serializeXML(schema, output, serializer.createExportContext((IProject)null, VERSION),
            new HashMap<String, UUID>(), "\n");
        return output.toString(StandardCharsets.UTF_8);
    }

    static DataCompositionSchema deserialize(String xml) throws Exception
    {
        return deserialize(xml, null);
    }

    static DataCompositionSchema deserialize(String xml, IDtProject project) throws Exception
    {
        var reader = new DcsV8Serializer(project, VERSION, (IResourceLookup)null);
        return reader.deserializeXML(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
