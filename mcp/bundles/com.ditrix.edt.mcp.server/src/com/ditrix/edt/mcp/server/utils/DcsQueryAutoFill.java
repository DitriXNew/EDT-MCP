/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.common.StringUtils;
import com._1c.g5.v8.dt.core.V8Commands;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.dcs.DcsDataSetInfoAvailableParameterInfo;
import com._1c.g5.v8.dt.dcs.DcsDataSetInfoException;
import com._1c.g5.v8.dt.dcs.DcsDataSetInfoV8LocalQuery;
import com._1c.g5.v8.dt.dcs.dataset.fields.EmptyQueryDataSetWrapperBase;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.schema.DcsPackage;
import com._1c.g5.v8.dt.dcs.path.DcsPath;
import com._1c.g5.v8.dt.dcs.settings.SettingsContext;
import com._1c.g5.v8.dt.dcs.typedvalue.TypedValueFactory;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.typeinfo.IMdTypeIndex;
import com._1c.g5.v8.dt.mcore.UndefinedValue;
import com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService;
import com.ditrix.edt.mcp.server.Activator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Synchronous designer query fill, joined to the caller's BM write and subsequent export. */
public final class DcsQueryAutoFill
{
    private DcsQueryAutoFill() { }

    public static int fill(ProjectContext.ConfigurationResult context, IBmModel model,
        IBmTransaction tx, IProgressMonitor monitor, DataCompositionSchema schema,
        DcsAddress address, String type, JsonObject body, String language)
    {
        List<DataCompositionSchemaDataSetQuery> targets = targets(schema, address, type, body);
        if (targets.isEmpty()) return 0;
        IV8Project project = Activator.getDefault().getV8ProjectManager().getProject(context.project());
        var injector = MdPlugin.getDefault().getInjector();
        SettingsContext settings = new SettingsContext(project.getDtProject(), model,
            injector.getInstance(IMdTypeIndex.class), injector.getInstance(IBmEmfIndexManager.class));
        int alias = project.getScriptVariant().getValue();
        IBmEditingContext editing = new IBmEditingContext()
        {
            @Override public <T> T execute(IBmTask<T> task) { return task.execute(tx, monitor); }
            @Override public IBmModel getModel() { return model; }
        };
        for (var dataSet : targets)
        {
            try
            {
                var info = new DcsDataSetInfoV8LocalQuery(settings);
                info.init(project, dataSet, language, alias, true);
                fillFields(dataSet, info, language, alias, editing);
                addParameters(schema, info.getAvailableParameters(), language, alias);
            }
            catch (DcsDataSetInfoException | RuntimeException e)
            {
                throw Refusals.state("Cannot auto-fill query data set '" + dataSet.getName()
                    + "': " + e.getMessage() + ". Run validate_query with dcsMode=true and resolve "
                    + "its sources, or supply fields/parameters explicitly with autoFillFields=false; "
                    + "nothing was written.");
            }
        }
        return targets.size();
    }

    static void fillFields(DataCompositionSchemaDataSetQuery dataSet, DcsDataSetInfoV8LocalQuery info,
        String language, int alias, IBmEditingContext editing)
    {
        var wrapper = new EmptyQueryDataSetWrapperBase(dataSet, false, language, alias);
        V8Commands.CompoundV8Command command = wrapper.fillDataSetFields(
            info.getAvailableUserFields(new DcsPath()));
        command.execute(editing);
    }

    /** The designer's Parameters.fillDSParameters creation rules; existing authored parameters survive. */
    static void addParameters(DataCompositionSchema schema,
        List<DcsDataSetInfoAvailableParameterInfo> available, String language, int alias)
    {
        for (var source : available)
        {
            DcsPath path = source.name[alias];
            if (path == null || path.isEmpty()) path = source.name[0];
            String name = path.toString();
            if (schema.getParameters().stream().anyMatch(p -> name.equalsIgnoreCase(p.getName()))) continue;
            var parameter = DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
            parameter.setName(name);
            var core = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE;
            var title = core.createPresentation();
            var text = core.createLocalString();
            text.getContent().put(language, StringUtils.nameToText(name));
            title.setLocalValue(text);
            parameter.setTitle(title);
            if (source.valueType != null)
            {
                var types = source.valueType.cloneSource().toTypeDescription();
                var allowed = TypeProviderService.INSTANCE.getTypeDescriptionInfo(parameter,
                    DcsPackage.Literals.DATA_COMPOSITION_SCHEMA_PARAMETER__VALUE_TYPE);
                types.getTypes().removeIf(type -> !allowed.contains(type));
                parameter.setValueType(types);
            }
            parameter.setValueListAllowed(source.valueListAllowed);
            parameter.setUseRestriction(source.mandatory);
            if (source.defaultValue instanceof UndefinedValue)
            {
                if (source.valueType != null) parameter.getValues().add(
                    TypedValueFactory.INSTANCE.createValue(source.valueType.toTypeDescription()));
            }
            else if (source.defaultValue != null)
                parameter.getValues().add(EcoreUtil.copy(source.defaultValue));
            schema.getParameters().add(parameter);
        }
    }

    static List<DataCompositionSchemaDataSetQuery> targets(DataCompositionSchema schema,
        DcsAddress address, String type, JsonObject body)
    {
        List<DataCompositionSchemaDataSetQuery> result = new ArrayList<>();
        if (body == null) return result;
        if ("schema".equals(type)) collect(schema.getDataSets(), body.get("dataSets"), result);
        else if ("dataSet".equals(type))
        {
            DcsAddress selected = address;
            String renamed = DcsWriter.stringMember(body, "name");
            var segments = new ArrayList<>(address.segments());
            if (renamed != null && !segments.isEmpty() && segments.size() % 2 == 0)
            {
                segments.set(segments.size() - 1, renamed);
                selected = DcsAddress.parse(DcsAddress.render(address.rootFqn(), segments)).address();
            }
            var node = DcsReadProjection.resolveOptionsNode(address.rootFqn(), schema, selected);
            if (node.value instanceof DataSet) collect((DataSet)node.value, body, result);
            else if (node.value instanceof List<?>)
                collectNamed((List<?>)node.value, body, result);
            else collectNamed(schema.getDataSets(), body, result);
        }
        return result;
    }

    private static void collect(List<? extends DataSet> dataSets, JsonElement bodies,
        List<DataCompositionSchemaDataSetQuery> result)
    {
        if (bodies == null || !bodies.isJsonArray()) return;
        for (var body : bodies.getAsJsonArray())
            if (body.isJsonObject()) collectNamed(dataSets, body.getAsJsonObject(), result);
    }

    private static void collectNamed(List<?> dataSets, JsonObject body,
        List<DataCompositionSchemaDataSetQuery> result)
    {
        String name = DcsWriter.stringMember(body, "name");
        for (var item : dataSets)
            if (item instanceof DataSet && ((DataSet)item).getName().equals(name))
                collect((DataSet)item, body, result);
    }

    private static void collect(DataSet dataSet, JsonObject body,
        List<DataCompositionSchemaDataSetQuery> result)
    {
        if (dataSet instanceof DataCompositionSchemaDataSetQuery
            && ((DataCompositionSchemaDataSetQuery)dataSet).isAutoFillAvailableFields()
            && (body.has("query") || body.has("autoFillFields")) && !result.contains(dataSet))
            result.add((DataCompositionSchemaDataSetQuery)dataSet);
        if (dataSet instanceof DataCompositionSchemaDataSetUnion)
            collect(((DataCompositionSchemaDataSetUnion)dataSet).getItems(), body.get("items"), result);
    }
}
