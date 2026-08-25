/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionGroupType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionPeriodAdditionType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionSortDirection;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionComparisonType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceUse;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFieldPlacement;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterApplicationType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemsGroupType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemState;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettingsItemViewMode;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameter;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.path.DcsPathException;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.EnumValue;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/** Builds the version-aware vocabularies accepted by DCS writes. */
public final class DcsOptions
{
    private DcsOptions()
    {
        // Utility class
    }

    public static Result render(String rootFqn, TargetKind targetKind, EObject root,
        DcsAddress address, String type, String configurationLanguage, Version version,
        Integer requestedLimit, int offset)
    {
        DcsReadProjection.OptionsNode node =
            DcsReadProjection.resolveOptionsNode(rootFqn, root, address);
        if (!node.isSuccess())
        {
            return Result.failure(node.error);
        }
        if (targetKind == TargetKind.FORM && !"conditionalAppearance".equals(type)) //$NON-NLS-1$
        {
            return Result.failure("A form DCS root supports options only for " //$NON-NLS-1$
                + "type='conditionalAppearance'; got type='" + type + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (address.hasPointer() && node.actualType != null && !type.equals(node.actualType))
        {
            return Result.failure("Type '" + type + "' does not match options target '" //$NON-NLS-1$ //$NON-NLS-2$
                + address + "' (its type is '" + node.actualType + "')."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        DcsPresentationParser.LanguageContext languages = new DcsPresentationParser.LanguageContext(
            Collections.singletonList(configurationLanguage == null ? "en" : configurationLanguage), //$NON-NLS-1$
            configurationLanguage == null ? "en" : configurationLanguage); //$NON-NLS-1$
        List<Option> options = new ArrayList<>();
        try
        {
            if ("conditionalAppearance".equals(type)) //$NON-NLS-1$
            {
                AppearanceCatalogue catalogue = targetKind == TargetKind.FORM
                    ? AppearanceCatalogue.FORM : targetKind == TargetKind.DYNAMIC_LIST
                        ? AppearanceCatalogue.DYNAMIC_LIST : AppearanceCatalogue.SCHEMA;
                addParameters(options, "appearance", //$NON-NLS-1$
                    DcsSettingsWriter.appearanceParameters(catalogue, version,
                        languages.resolvedCode()), languages);
            }
            if ("outputParameter".equals(type) || "userSettings".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
                || "grouping".equals(type) || "table".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                OutputParameterCatalogue catalogue = outputCatalogue(type, address, node.value,
                    node.owner);
                if (catalogue == null)
                {
                    return Result.failure("Address '" + address //$NON-NLS-1$
                        + "' is not an output-parameter, grouping, or table holder."); //$NON-NLS-1$
                }
                if (catalogue == OutputParameterCatalogue.CHART
                    || catalogue == OutputParameterCatalogue.CHART_GROUP)
                {
                    return Result.failure("The platform exposes chart output-parameter catalogues, " //$NON-NLS-1$
                        + "but this tool deliberately refuses chart authoring. action='options' " //$NON-NLS-1$
                        + "cannot list them as writable choices until chart writes are supported."); //$NON-NLS-1$
                }
                addParameters(options, "output parameter", //$NON-NLS-1$
                    DcsSettingsWriter.outputParameters(catalogue, version,
                        languages.resolvedCode()), languages);
            }
        }
        catch (DcsPathException | RuntimeException e)
        {
            return Result.failure("Could not load DCS options for platform " //$NON-NLS-1$
                + (version == null ? Version.LATEST : version) + ": " + e.getMessage()); //$NON-NLS-1$
        }
        addBodyEnums(options, type);

        int limit = Pagination.clampLimit(requestedLimit == null ? Pagination.DEFAULT_LIMIT
            : requestedLimit.intValue(), Pagination.MAX_LIMIT);
        int start = Math.min(offset, options.size());
        int end = Math.min(options.size(), start + limit);
        StringBuilder markdown = new StringBuilder();
        markdown.append("# DCS write options\n\n") //$NON-NLS-1$
            .append("**Address:** `").append(address).append("`\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Type:** `").append(type).append("`\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Configuration language:** `").append(languages.configurationCode()) //$NON-NLS-1$
            .append("`\n\n") //$NON-NLS-1$
            .append("Parameter names below are the spelling a write stores in this project. ") //$NON-NLS-1$
            .append("The other English/Russian spelling is also accepted on input.\n\n") //$NON-NLS-1$
            .append("Declared value type is the concrete value accepted by writes; when platform ") //$NON-NLS-1$
            .append("metadata carries multiple entries, this is the writable catalogue default.\n\n") //$NON-NLS-1$
            .append(MarkdownUtils.tableHeader("Kind", "Name/member", "Declared value type", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Allowed platform literals")); //$NON-NLS-1$
        for (int i = start; i < end; i++)
        {
            Option option = options.get(i);
            markdown.append(MarkdownUtils.tableRow(option.kind, option.name, option.valueType,
                String.join(", ", option.literals))); //$NON-NLS-1$
        }
        markdown.append("\n**Total:** ").append(options.size()).append(" options\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            .append("**Page:** offset ").append(start).append(", showing ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(end - start).append("\n"); //$NON-NLS-1$
        if (end < options.size())
        {
            markdown.append("\n**Next offset:** `").append(end).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return Result.success(markdown.toString());
    }

    private static void addParameters(List<Option> result, String kind,
        DcsAvailableParameterCollection parameters,
        DcsPresentationParser.LanguageContext languages)
    {
        for (int i = 0; i < parameters.itemsCount(); i++)
        {
            DcsAvailableParameter parameter = parameters.getItemAt(i);
            Value value = parameter.defValue();
            Enumerator enumerator = value instanceof EnumValue ? ((EnumValue)value).getValue() : null;
            result.add(new Option(kind, DcsSettingsWriter.parameterName(parameter, languages),
                declaredValueType(parameter, value, enumerator),
                DcsSettingsWriter.enumeratorLiterals(enumerator)));
        }
    }

    /**
     * Describes the value the writer actually validates, rather than the EMF records in the
     * catalogue's type description. The latter can contain anonymous {@code Type} entries and can
     * describe a wider union than the catalogue's single writable default value (notably Text).
     */
    private static String declaredValueType(DcsAvailableParameter parameter, Value value,
        Enumerator enumerator)
    {
        if (value instanceof ColorValue) return "Color"; //$NON-NLS-1$
        if (value instanceof FontValue) return "Font"; //$NON-NLS-1$
        if (value instanceof BooleanValue) return "Boolean"; //$NON-NLS-1$
        if (value instanceof NumberValue) return "Number"; //$NON-NLS-1$
        if (value instanceof StringValue) return "String"; //$NON-NLS-1$
        if (value instanceof DateValue) return "Date"; //$NON-NLS-1$
        if (value instanceof LocalString)
        {
            return "LocalString"; //$NON-NLS-1$
        }
        if (value instanceof EnumValue)
        {
            String platformType = usefulPlatformType(parameter);
            if (!platformType.isEmpty()) return platformType;
            return enumerator == null ? "platform enum" : enumerator.getClass().getSimpleName(); //$NON-NLS-1$
        }
        String platformType = usefulPlatformType(parameter);
        if (!platformType.isEmpty()) return platformType;
        if (value == null) return "unknown"; //$NON-NLS-1$
        String modelType = value.eClass().getName();
        return modelType.endsWith("Value") //$NON-NLS-1$
            ? modelType.substring(0, modelType.length() - "Value".length()) : modelType; //$NON-NLS-1$
    }

    private static String usefulPlatformType(DcsAvailableParameter parameter)
    {
        String declared = DcsStructureReader.describeType(parameter.getType());
        if (declared.isEmpty()) return ""; //$NON-NLS-1$
        List<String> concepts = new ArrayList<>();
        for (String candidate : declared.split(",")) //$NON-NLS-1$
        {
            String concept = candidate.trim();
            if (!concept.isEmpty() && !"Type".equals(concept) && !concepts.contains(concept)) //$NON-NLS-1$
            {
                concepts.add(concept);
            }
        }
        return concepts.size() == 1 ? concepts.get(0) : ""; //$NON-NLS-1$
    }

    private static OutputParameterCatalogue outputCatalogue(String type, DcsAddress address,
        Object value, EObject owner)
    {
        if (!address.hasPointer())
        {
            if ("grouping".equals(type)) return OutputParameterCatalogue.GROUP; //$NON-NLS-1$
            if ("table".equals(type)) return OutputParameterCatalogue.TABLE; //$NON-NLS-1$
            return OutputParameterCatalogue.SETTINGS;
        }
        EObject current = value instanceof EObject ? (EObject)value : owner;
        while (current != null)
        {
            String name = current.eClass().getName();
            if (name.contains("ChartGroup")) return OutputParameterCatalogue.CHART_GROUP; //$NON-NLS-1$
            if (name.contains("Chart")) return OutputParameterCatalogue.CHART; //$NON-NLS-1$
            if (name.contains("TableGroup")) return OutputParameterCatalogue.TABLE_GROUP; //$NON-NLS-1$
            if (name.contains("Table")) return OutputParameterCatalogue.TABLE; //$NON-NLS-1$
            if (name.contains("GroupOutputParameter") //$NON-NLS-1$
                || "DataCompositionGroup".equals(name)) //$NON-NLS-1$
            {
                return OutputParameterCatalogue.GROUP;
            }
            if (name.contains("OutputParameterValues") //$NON-NLS-1$
                || "DataCompositionSettings".equals(name)) //$NON-NLS-1$
            {
                return OutputParameterCatalogue.SETTINGS;
            }
            current = current.eContainer();
        }
        return null;
    }

    private static void addBodyEnums(List<Option> result, String type)
    {
        switch (type)
        {
            case "userSettings": //$NON-NLS-1$
                addEnum(result, "itemsViewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                break;
            case "grouping": //$NON-NLS-1$
                addEnum(result, "groupState", DataCompositionSettingsItemState.values()); //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "itemsViewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "groupFields.items[].groupType", DataCompositionGroupType.values()); //$NON-NLS-1$
                addEnum(result, "groupFields.items[].periodAdditionType", //$NON-NLS-1$
                    DataCompositionPeriodAdditionType.values());
                break;
            case "selection": //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "placement", DataCompositionFieldPlacement.values()); //$NON-NLS-1$
                break;
            case "filter": //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "application", DataCompositionFilterApplicationType.values()); //$NON-NLS-1$
                addEnum(result, "groupType", DataCompositionFilterItemsGroupType.values()); //$NON-NLS-1$
                addEnum(result, "comparisonType", DataCompositionComparisonType.values()); //$NON-NLS-1$
                break;
            case "order": //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "orderType", DataCompositionSortDirection.values()); //$NON-NLS-1$
                break;
            case "dataParameter": //$NON-NLS-1$
            case "outputParameter": //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                break;
            case "conditionalAppearance": //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                for (String member : new String[] {"useInGroup", "useInHierarchicalGroup", //$NON-NLS-1$ //$NON-NLS-2$
                    "useInOverall", "useInFieldsHeader", "useInHeader", "useInParameters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "useInFilter", "useInResourceFieldsHeader", "useInOverallHeader", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "useInOverallResourceFieldsHeader"}) //$NON-NLS-1$
                {
                    addEnum(result, member, DataCompositionConditionalAppearanceUse.values());
                }
                break;
            case "table": //$NON-NLS-1$
                addEnum(result, "viewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "rowsViewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                addEnum(result, "columnsViewMode", DataCompositionSettingsItemViewMode.values()); //$NON-NLS-1$
                break;
            default:
                break;
        }
    }

    private static <T extends Enum<T> & Enumerator> void addEnum(List<Option> result, String member,
        T[] values)
    {
        if (values.length == 0) return;
        result.add(new Option("body enum", member, //$NON-NLS-1$
            values[0].getClass().getSimpleName(),
            DcsSettingsWriter.enumeratorLiterals(values[0])));
    }

    private static final class Option
    {
        final String kind;
        final String name;
        final String valueType;
        final List<String> literals;

        Option(String kind, String name, String valueType, List<String> literals)
        {
            this.kind = kind;
            this.name = name;
            this.valueType = valueType;
            this.literals = literals;
        }
    }

    public static final class Result
    {
        private final String markdown;
        private final String error;

        private Result(String markdown, String error)
        {
            this.markdown = markdown;
            this.error = error;
        }

        static Result success(String markdown)
        {
            return new Result(markdown, null);
        }

        static Result failure(String error)
        {
            return new Result(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public String markdown()
        {
            return markdown;
        }

        public String error()
        {
            return error;
        }
    }
}
