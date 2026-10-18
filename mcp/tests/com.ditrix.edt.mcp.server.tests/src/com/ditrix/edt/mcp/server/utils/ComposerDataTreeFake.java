/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.model.PropertyInfo.PropertyInfoType;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTabularSection;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/** A declared data tree; no EDT session or type-name whitelist supplies its answers. */
public final class ComposerDataTreeFake implements AutoCloseable
{
    private final Map<String, PropertyInfo> paths = new HashMap<>();
    private final IDataSourceInfoAssociationService service = mock(IDataSourceInfoAssociationService.class);
    private Field seam;
    private Object original;

    public ComposerDataTreeFake()
    {
        when(service.findPropertyInfo(any(Form.class), any(AbstractDataPath.class))).thenAnswer(
            call -> paths.get(key(String.join(".", ((AbstractDataPath)call.getArgument(1)).getSegments()))));
    }

    public ComposerDataTreeFake path(String path, boolean table)
    {
        PropertyInfo info = mock(PropertyInfo.class);
        when(info.getType()).thenReturn(table ? PropertyInfoType.COMMON_TABLE_TYPE_PROPERTY
            : PropertyInfoType.COMMON_PROPERTY);
        when(info.getDataPath(ScriptVariant.ENGLISH)).thenAnswer(
            call -> dataPath(path.replaceAll("\\[\\d+\\]", "")));
        paths.put(key(path), info);
        return this;
    }

    public ComposerDataTreeFake alias(String english, String russian, boolean table)
    {
        path(english, table);
        paths.put(key(russian), paths.get(key(english)));
        return this;
    }

    public ComposerDataTreeFake tabularSection(String path)
    {
        path(path, true);
        when(paths.get(key(path)).getSource()).thenReturn(mock(BasicTabularSection.class));
        return this;
    }

    public void install() throws ReflectiveOperationException
    {
        install(() -> service);
    }

    public void unavailable(boolean returnsNull) throws ReflectiveOperationException
    {
        install(() -> {
            if (returnsNull)
            {
                return null;
            }
            throw new IllegalStateException("data service unavailable");
        });
    }

    private void install(Supplier<IDataSourceInfoAssociationService> source) throws ReflectiveOperationException
    {
        try
        {
            seam = FormElementWriter.class.getDeclaredField("dataSourcesSource");
        }
        catch (NoSuchFieldException e)
        {
            // The base revision has no data-tree branch. Keep it executable for behavioral red tests.
            return;
        }
        seam.setAccessible(true);
        if (original == null)
        {
            original = seam.get(null);
        }
        seam.set(null, source);
    }

    @Override
    public void close() throws IllegalAccessException
    {
        if (seam != null)
        {
            seam.set(null, original);
        }
    }

    public static Form form(String name, String type)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        attribute(form, name, type);
        return form;
    }

    public static FormAttribute attribute(Form form, String name, String typeName)
    {
        FormAttribute attribute = FormFactory.eINSTANCE.createFormAttribute();
        attribute.setName(name);
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        description.getTypes().add(type);
        attribute.setValueType(description);
        form.getAttributes().add(attribute);
        return attribute;
    }

    public static DataPath dataPath(String path)
    {
        DataPath result = FormFactory.eINSTANCE.createDataPath();
        result.getSegments().addAll(Arrays.asList(path.split("\\.", -1)));
        return result;
    }

    private static String key(String path)
    {
        return path.toLowerCase(Locale.ROOT);
    }
}
