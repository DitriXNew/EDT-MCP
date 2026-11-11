/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;

/**
 * Read-only projection of a schema's area templates ({@code templates}) and the grouping
 * bindings that point at them ({@code groupTemplates}, {@code groupHeaderTemplates}) for
 * {@link DcsReadProjection} (#662).
 *
 * <p>They are read under {@code type='schema'}, as data-set links are: the typed write vocabulary
 * has no template type, and the lossless XML channel is the only way to author them. A template is
 * addressed by its {@code name}, a binding by its zero-based index. A template page summarises the
 * area body instead of expanding it, because its rows, cells and appearance have no addressable read
 * type; the body itself is read with {@code format='xml'}.
 *
 * <p>The area-template classes live in a package this bundle does not import, so the body is read
 * through the model's features rather than its typed accessors.
 */
public final class DcsTemplateProjection
{
    static final String TEMPLATES = "templates"; //$NON-NLS-1$
    static final String GROUP_TEMPLATES = "groupTemplates"; //$NON-NLS-1$
    static final String GROUP_HEADER_TEMPLATES = "groupHeaderTemplates"; //$NON-NLS-1$

    private static final String UNSET = "(unset)"; //$NON-NLS-1$

    private DcsTemplateProjection()
    {
        // utility class
    }

    /**
     * Refuses a typed write addressed at a template or a grouping binding, before any model work:
     * the reads print these addresses, and without this a write there would fall through to a
     * writer that does not model them.
     *
     * @param action the requested write action
     * @param address the parsed target address
     * @return the refusal text, or {@code null} when the address is not a template address
     */
    public static String writeRefusal(String action, DcsAddress address)
    {
        if (address == null || !address.hasPointer() || !isCollection(address.segments().get(0)))
        {
            return null;
        }
        String root = DcsAddress.render(address.rootFqn(), Collections.<String> emptyList());
        return "Template address '" + address + "' is read-only: the typed API does not author " //$NON-NLS-1$ //$NON-NLS-2$
            + "templates or grouping bindings, so action='" + action + "' was not run and nothing " //$NON-NLS-1$ //$NON-NLS-2$
            + "was written. Read it with action='get', type='schema'. To change it, read the whole " //$NON-NLS-1$
            + "schema with action='get', type='schema', format='xml' on '" + root //$NON-NLS-1$
            + "', edit the XML, and send it back with action='replace', type='schema', " //$NON-NLS-1$
            + "body={xml:...} and the expectedHash of that read."; //$NON-NLS-1$
    }

    /** @return whether {@code collection} is one of the schema's template collections */
    static boolean isCollection(String collection)
    {
        return TEMPLATES.equals(collection) || GROUP_TEMPLATES.equals(collection)
            || GROUP_HEADER_TEMPLATES.equals(collection);
    }

    /** @return whether {@code value} is a template description or a grouping binding */
    static boolean isNode(Object value)
    {
        return value instanceof DataCompositionSchemaTemplateDescription
            || value instanceof DataCompositionSchemaGroupTemplate;
    }

    /** A template's selector: its name, or its index when the name is empty. */
    static String templateSelector(EObject template, int index)
    {
        String name = template instanceof DataCompositionSchemaTemplateDescription
            ? ((DataCompositionSchemaTemplateDescription)template).getName() : null;
        return name == null || name.isEmpty() ? Integer.toString(index) : name;
    }

    /** Summary-table rows {@code Name | Summary | Address} for the schema's templates. */
    static List<String> templateRows(DataCompositionSchema schema, String schemaAddress)
    {
        List<String> rows = new ArrayList<>();
        String collection = DcsReadProjection.child(schemaAddress, TEMPLATES);
        for (int i = 0; i < schema.getTemplates().size(); i++)
        {
            DataCompositionSchemaTemplateDescription template = schema.getTemplates().get(i);
            String address = DcsReadProjection.child(collection, templateSelector(template, i));
            rows.add(MarkdownUtils.tableRow(
                DcsReadProjection.boundedTableCell(nameOf(template), address), summary(template),
                address));
        }
        return rows;
    }

    /** Summary-table rows {@code Address | Binding} for one binding collection. */
    static List<String> bindingRows(DataCompositionSchema schema, String schemaAddress,
        String collectionName)
    {
        List<DataCompositionSchemaGroupTemplate> bindings = bindings(schema, collectionName);
        List<String> rows = new ArrayList<>();
        String collection = DcsReadProjection.child(schemaAddress, collectionName);
        for (int i = 0; i < bindings.size(); i++)
        {
            String address = DcsReadProjection.child(collection, Integer.toString(i));
            rows.add(MarkdownUtils.tableRow(address,
                DcsReadProjection.boundedTableCell(binding(bindings.get(i)), address)));
        }
        return rows;
    }

    /** One-line body summary, for example {@code 1 row, 2 cells, 1 parameter}. */
    static String summary(EObject template)
    {
        Body body = Body.of(template);
        return count(body.rows, "row") + ", " + count(body.cells, "cell") + ", " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + count(body.parameters.size(), "parameter"); //$NON-NLS-1$
    }

    /** One-line binding, for example {@code Totals · Header → TotalRow}. */
    static String binding(DataCompositionSchemaGroupTemplate binding)
    {
        String grouping = binding.getGroupName();
        if (grouping == null || grouping.isEmpty())
        {
            String fields = groupFields(binding);
            grouping = fields.isEmpty() ? UNSET : "fields " + fields; //$NON-NLS-1$
        }
        return grouping + " · " + (binding.getTemplateType() == null ? UNSET //$NON-NLS-1$
            : binding.getTemplateType().getLiteral()) + " → " + orUnset(binding.getTemplate()); //$NON-NLS-1$
    }

    /**
     * Appends the template or binding page sections after the node's property table.
     *
     * @param result the node page being rendered
     * @param node a template description or a grouping binding
     * @param nodeAddress the node's canonical address
     * @param schemaAddress the canonical address of the schema that contains it
     */
    static void appendNode(StringBuilder result, EObject node, String nodeAddress,
        String schemaAddress)
    {
        EObject owner = node.eContainer();
        DataCompositionSchema schema = owner instanceof DataCompositionSchema
            ? (DataCompositionSchema)owner : null;
        if (node instanceof DataCompositionSchemaTemplateDescription)
        {
            appendTemplate(result, (DataCompositionSchemaTemplateDescription)node, schema,
                nodeAddress, schemaAddress);
        }
        else if (node instanceof DataCompositionSchemaGroupTemplate)
        {
            appendBinding(result, (DataCompositionSchemaGroupTemplate)node, schema, nodeAddress,
                schemaAddress);
        }
    }

    private static void appendTemplate(StringBuilder result,
        DataCompositionSchemaTemplateDescription template, DataCompositionSchema schema,
        String nodeAddress, String schemaAddress)
    {
        Body body = Body.of(template);
        result.append("## Template\n\n") //$NON-NLS-1$
            .append("Read-only: the typed API does not author templates. The area body (rows, cells, ") //$NON-NLS-1$
            .append("their items and appearance) is summarised, not expanded; read it with ") //$NON-NLS-1$
            .append("format='xml' on the bare schema root `").append(schemaAddress).append("`.\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append(MarkdownUtils.tableHeader("Property", "Value")) //$NON-NLS-1$ //$NON-NLS-2$
            .append(MarkdownUtils.tableRow("Body", //$NON-NLS-1$
                body.node == null ? "(none)" : body.node.eClass().getName())) //$NON-NLS-1$
            .append(MarkdownUtils.tableRow("Rows", Integer.toString(body.rows))) //$NON-NLS-1$
            .append(MarkdownUtils.tableRow("Cells", Integer.toString(body.cells))) //$NON-NLS-1$
            .append(MarkdownUtils.tableRow("Parameters", Integer.toString(body.parameters.size()))) //$NON-NLS-1$
            .append('\n');

        result.append("## Template parameters\n\n"); //$NON-NLS-1$
        if (body.parameters.isEmpty())
        {
            result.append("_(no parameters)_\n\n"); //$NON-NLS-1$
        }
        else
        {
            result.append(MarkdownUtils.tableHeader("Name", "Kind", "Expression")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (Object item : body.parameters)
            {
                EObject parameter = asEObject(item);
                if (parameter == null) continue;
                result.append(MarkdownUtils.tableRow(
                    DcsReadProjection.boundedTableCell(orUnset(string(parameter, "name")), nodeAddress), //$NON-NLS-1$
                    parameter.eClass().getName(),
                    DcsReadProjection.boundedTableCell(parameterExpression(parameter), nodeAddress)));
            }
            result.append('\n');
        }

        result.append("## Grouping bindings\n\n"); //$NON-NLS-1$
        List<String> bound = new ArrayList<>();
        String name = template.getName();
        if (schema != null && name != null && !name.isEmpty())
        {
            for (String collectionName : List.of(GROUP_TEMPLATES, GROUP_HEADER_TEMPLATES))
            {
                List<DataCompositionSchemaGroupTemplate> bindings = bindings(schema, collectionName);
                String collection = DcsReadProjection.child(schemaAddress, collectionName);
                for (int i = 0; i < bindings.size(); i++)
                {
                    DataCompositionSchemaGroupTemplate binding = bindings.get(i);
                    if (name.equals(binding.getTemplate()))
                    {
                        String address = DcsReadProjection.child(collection, Integer.toString(i));
                        bound.add(MarkdownUtils.tableRow(address,
                            DcsReadProjection.boundedTableCell(binding(binding), address)));
                    }
                }
            }
        }
        if (bound.isEmpty())
        {
            result.append("_(no grouping binds this template)_\n"); //$NON-NLS-1$
            return;
        }
        result.append(MarkdownUtils.tableHeader("Address", "Binding")); //$NON-NLS-1$ //$NON-NLS-2$
        bound.forEach(result::append);
    }

    private static void appendBinding(StringBuilder result,
        DataCompositionSchemaGroupTemplate binding, DataCompositionSchema schema,
        String nodeAddress, String schemaAddress)
    {
        String fields = groupFields(binding);
        result.append("## Grouping binding\n\n") //$NON-NLS-1$
            .append("Read-only: the typed API does not author grouping bindings; read them with ") //$NON-NLS-1$
            .append("format='xml' on the bare schema root `").append(schemaAddress).append("`.\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append(MarkdownUtils.tableHeader("Property", "Value")) //$NON-NLS-1$ //$NON-NLS-2$
            .append(MarkdownUtils.tableRow("Group fields", //$NON-NLS-1$
                DcsReadProjection.boundedTableCell(fields.isEmpty() ? "(none)" : fields, nodeAddress))) //$NON-NLS-1$
            .append(MarkdownUtils.tableRow("Template address", //$NON-NLS-1$
                DcsReadProjection.boundedTableCell(templateAddress(binding.getTemplate(), schema,
                    schemaAddress), nodeAddress)));
    }

    /** The area body of one template and the counts a read reports for it. */
    private static final class Body
    {
        final EObject node;
        final int rows;
        final int cells;
        final List<?> parameters;

        private Body(EObject node, int rows, int cells, List<?> parameters)
        {
            this.node = node;
            this.rows = rows;
            this.cells = cells;
            this.parameters = parameters;
        }

        static Body of(EObject template)
        {
            EObject node = asEObject(feature(template, "template")); //$NON-NLS-1$
            int rows = 0;
            int cells = 0;
            for (Object item : list(node, "items")) //$NON-NLS-1$
            {
                rows++;
                cells += list(asEObject(item), "cells").size(); //$NON-NLS-1$
            }
            return new Body(node, rows, cells, list(template, "parameters")); //$NON-NLS-1$
        }
    }

    private static String templateAddress(String name, DataCompositionSchema schema,
        String schemaAddress)
    {
        if (name == null || name.isEmpty())
        {
            return UNSET;
        }
        if (schema != null)
        {
            for (int i = 0; i < schema.getTemplates().size(); i++)
            {
                DataCompositionSchemaTemplateDescription template = schema.getTemplates().get(i);
                if (name.equals(template.getName()))
                {
                    return DcsReadProjection.child(DcsReadProjection.child(schemaAddress, TEMPLATES),
                        templateSelector(template, i));
                }
            }
        }
        return "(no template named '" + name + "' in this schema)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String parameterExpression(EObject parameter)
    {
        EStructuralFeature expression = parameter.eClass().getEStructuralFeature("expression"); //$NON-NLS-1$
        if (expression != null)
        {
            Object value = parameter.eGet(expression);
            return value == null ? "" : value.toString(); //$NON-NLS-1$
        }
        EStructuralFeature fields = parameter.eClass().getEStructuralFeature("fieldExpression"); //$NON-NLS-1$
        if (fields != null)
        {
            return "(details: " + count(list(parameter, "fieldExpression").size(), //$NON-NLS-1$ //$NON-NLS-2$
                "field expression") + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ""; //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static List<DataCompositionSchemaGroupTemplate> bindings(DataCompositionSchema schema,
        String collectionName)
    {
        Object value = feature(schema, collectionName);
        return value instanceof List<?> ? (List<DataCompositionSchemaGroupTemplate>)value
            : Collections.<DataCompositionSchemaGroupTemplate> emptyList();
    }

    private static String groupFields(DataCompositionSchemaGroupTemplate binding)
    {
        List<String> values = new ArrayList<>();
        for (DataCompositionField field : binding.getGroupFields())
        {
            values.add(field == null || field.getValue() == null ? UNSET : field.getValue());
        }
        return String.join(", ", values); //$NON-NLS-1$
    }

    private static String nameOf(DataCompositionSchemaTemplateDescription template)
    {
        return orUnset(template.getName());
    }

    private static String orUnset(String value)
    {
        return value == null || value.isEmpty() ? UNSET : value;
    }

    private static String count(int count, String noun)
    {
        return count + " " + noun + (count == 1 ? "" : "s"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static Object feature(EObject object, String name)
    {
        if (object == null) return null;
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        return feature == null ? null : object.eGet(feature);
    }

    private static String string(EObject object, String name)
    {
        Object value = feature(object, name);
        return value == null ? "" : value.toString(); //$NON-NLS-1$
    }

    private static List<?> list(EObject object, String name)
    {
        Object value = feature(object, name);
        return value instanceof List<?> ? (List<?>)value : Collections.emptyList();
    }

    private static EObject asEObject(Object value)
    {
        return value instanceof EObject ? (EObject)value : null;
    }
}
