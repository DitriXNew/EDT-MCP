/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormAttributeAdditionalColumns;
import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.model.PropertyInfo.PropertyInfoType;
import com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTabularSection;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/** Real EDT EMF model (including tablePath containment), with only the derived data tree stubbed. */
public final class AdditionalColumnFixture implements AutoCloseable
{
    public static final String PREFIX = "Catalog.Catalog.Form.ItemForm.Attribute.Object.Column.";
    public final Form form = ComposerDataTreeFake.form("Object", "CatalogObject.Catalog");
    public final FormAttribute owner = form.getAttributes().get(0);
    public final IDataSourceInfoAssociationService sources = mock(IDataSourceInfoAssociationService.class);
    public final FormAttributeManagementService management = mock(FormAttributeManagementService.class);
    public final PropertyInfo root = property("Object", false);
    public final PropertyInfo table = property("Goods", true);
    public final PropertyInfo product = property("Product", false);
    public final List<PropertyInfo> children = new ArrayList<>();
    private final Supplier<IDataSourceInfoAssociationService> originalSources = FormElementWriter.dataSourcesSource;
    private final Supplier<FormAttributeManagementService> originalManagement =
        FormElementWriter.attributeManagementSource;

    public AdditionalColumnFixture() throws Exception
    {
        owner.setId(3);
        when(product.getNameRu()).thenReturn("\u0422\u043e\u0432\u0430\u0440");
        children.add(product);
        PropertyInfo line = property("LineNumber", false);
        when(line.getNameRu()).thenReturn("\u041d\u043e\u043c\u0435\u0440\u0421\u0442\u0440\u043e\u043a\u0438");
        children.add(line);
        when(table.getSource()).thenReturn(mock(BasicTabularSection.class));
        when(table.getPropertyInfos()).thenReturn(children);
        when(root.getPropertyInfos()).thenReturn(List.of(table));
        when(management.isSupportingColumn(table)).thenReturn(true);
        when(sources.findPropertyInfo(any(Form.class), any(AbstractDataPath.class))).thenAnswer(call -> {
            List<String> path = ((AbstractDataPath)call.getArgument(1)).getSegments();
            if (path.size() == 1 && path.get(0).equalsIgnoreCase("Object"))
            {
                return root;
            }
            // Deliberately exact: tests must exercise the writer's case-insensitive fallback too.
            return path.equals(List.of("Object", "Goods")) ? table : null;
        });
        // Compile-time bound: a renamed seam fails the build instead of silently not being injected.
        FormElementWriter.dataSourcesSource = () -> sources;
        FormElementWriter.attributeManagementSource = () -> management;
    }

    public void unavailable(boolean missingTree) throws Exception
    {
        if (missingTree)
        {
            doReturn(null).when(sources).findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
        }
        else
        {
            doThrow(new IllegalStateException("not built")).when(sources)
                .findPropertyInfo(any(Form.class), any(AbstractDataPath.class));
        }
    }

    public static PropertyInfo property(String name, boolean table)
    {
        PropertyInfo info = mock(PropertyInfo.class);
        when(info.getName()).thenReturn(name);
        when(info.getType()).thenReturn(table ? PropertyInfoType.COMMON_TABLE_TYPE_PROPERTY
            : PropertyInfoType.COMMON_PROPERTY);
        return info;
    }

    public FormAttributeAdditionalColumns entry(String... segments)
    {
        FormAttributeAdditionalColumns entry = FormFactory.eINSTANCE.createFormAttributeAdditionalColumns();
        entry.setTablePath(ComposerDataTreeFake.dataPath(String.join(".", segments)));
        owner.getAdditionalColumns().add(entry);
        return entry;
    }

    public static FormAttributeColumn column(String name, int id)
    {
        FormAttributeColumn column = FormFactory.eINSTANCE.createFormAttributeColumn();
        column.setName(name);
        column.setId(id);
        return column;
    }

    public static FormMemberRef ref(String address)
    {
        FormMemberRef ref = FormElementWriter.parse(address);
        assertNotNull("additional-column address must parse: " + address, ref);
        return ref;
    }

    public String create(String name, String tableToken, String[] kind)
    {
        return create(name, tableToken, kind, null);
    }

    public String create(String name, String tableToken, String[] kind, String[] tablePath)
    {
        return FormElementWriter.createMember(form, Kind.COLUMN, name, "Object", null, "en", "Extra title",
            false, kind, tableToken, tablePath);
    }

    @Override
    public void close()
    {
        FormElementWriter.dataSourcesSource = originalSources;
        FormElementWriter.attributeManagementSource = originalManagement;
    }
}
