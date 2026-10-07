/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormAttributeAdditionalColumns;
import com._1c.g5.v8.dt.form.model.FormAttributeExtInfo;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.form.service.extension.IFormExtensionService;
import com._1c.g5.v8.dt.form.service.item.FormItemVisitor;
import com._1c.g5.v8.dt.form.service.item.IFormItemCommand;
import com._1c.g5.v8.dt.form.util.DatapathUtil;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/**
 * EDT's own delete of a form attribute or attribute column - the one the form designer runs
 * ({@code FormAttributeManagementService.deleteAttribute} with {@code removeItems=true}) - and the
 * read-only prediction of what it removes. The removed items come from the platform's own collector;
 * the additional columns and the extInfo objects follow the same service's rules, cited where read.
 * Obtained from {@code EdtServices.getFormAttributeDeletion()}.
 */
public final class FormAttributeDeletion
{
    /**
     * The extInfo references whose object {@code ExtInfoManagementService.detachExtInfoObjectFromTransaction}
     * detaches as a separate BM top object: the features of its {@code getExtInfoFactory} factories.
     */
    private static final List<EReference> DETACHED_EXT_INFO_FEATURES = List.of(
        FormPackage.Literals.CHART_EXT_INFO__CHART,
        FormPackage.Literals.DENDROGRAM_EXT_INFO__DENDROGRAM,
        FormPackage.Literals.GANTT_CHART_EXT_INFO__GANTT_CHART,
        FormPackage.Literals.PLANNER_EXT_INFO__PLANNER_SETTINGS,
        FormPackage.Literals.SPREADSHEET_DOCUMENT_EXT_INFO__SPREADSHEET_DATA,
        FormPackage.Literals.GEOGRAPHICAL_SCHEMA_EXT_INFO__GEOGRAPHICAL_SCHEMA,
        FormPackage.Literals.GRAPHICAL_SCHEME_EXT_INFO__GRAPHICAL_SCHEME,
        FormPackage.Literals.DYNAMIC_LIST_EXT_INFO__LIST_SETTINGS);

    /** What EDT's delete of one attribute will do besides removing it, read without mutating. */
    public static final class Plan
    {
        /** The data items EDT removes, in visit order: its collector's result set. */
        public final List<EObject> bound = new ArrayList<>();

        /** The owner's additional-column entries a COLUMN's delete drops (their table path starts with it). */
        public final List<EObject> additionalColumns = new ArrayList<>();

        /** The separate BM objects EDT detaches with the attribute's extInfo. */
        public final List<Detached> detached = new ArrayList<>();

        /** EDT keeps the bound items: an extension form's attribute adopted from the base form. */
        public boolean itemsKept;

        /** The attribute's data path does not resolve, so EDT removes no item and no column. */
        public boolean unresolved;
    }

    /** One BM top object EDT detaches with an attribute's extInfo. */
    public static final class Detached
    {
        /** The extInfo's EClass name, e.g. {@code DynamicListExtInfo}. */
        public final String extInfo;

        /** The extInfo feature holding the object, e.g. {@code listSettings}. */
        public final String feature;

        /** The detached object. */
        public final EObject object;

        Detached(String extInfo, String feature, EObject object)
        {
            this.extInfo = extInfo;
            this.feature = feature;
            this.object = object;
        }
    }

    private final FormAttributeManagementService service;
    private final IDataSourceInfoAssociationService associations;
    private final IFormExtensionService extensions;
    private final Constructor<?> collector;

    /**
     * @param service EDT's form-attribute service
     * @param associations the form data-source association service (path resolution)
     * @param extensions the form extension service
     * @param collector the constructor of EDT's {@code DeleteDataItemByPathDathPrefixCommand}
     */
    public FormAttributeDeletion(FormAttributeManagementService service,
        IDataSourceInfoAssociationService associations, IFormExtensionService extensions,
        Constructor<?> collector)
    {
        this.service = service;
        this.associations = associations;
        this.extensions = extensions;
        this.collector = collector;
    }

    /**
     * Runs EDT's delete inside the caller's write transaction.
     *
     * @param tx the open BM write transaction
     * @param attribute the tx-bound {@code AbstractFormAttribute}
     */
    public void delete(IBmTransaction tx, EObject attribute)
    {
        service.deleteAttribute(tx, (AbstractFormAttribute)attribute, true);
    }

    /**
     * Predicts the delete without mutating, step by step as {@code deleteAttribute} runs: the items
     * EDT's own collector picks (skipped for an attribute an extension form adopted), the owner's
     * additional columns a column's delete drops, and the extInfo object it detaches. Which surviving
     * items EDT's cleaner unbinds is not predicted - the confirmed delete observes it.
     *
     * @param formModel the tx-bound content {@code Form}
     * @param attributeObject the tx-bound attribute or column
     * @return the plan
     */
    public Plan plan(EObject formModel, EObject attributeObject)
    {
        Form form = (Form)formModel;
        AbstractFormAttribute attribute = (AbstractFormAttribute)attributeObject;
        Plan plan = new Plan();
        PropertyInfo info = associations.findPropertyInfo(form, attribute);
        plan.unresolved = info == null;
        if (extensions.isExtensionAdopted(attribute) && !extensions.isPureExtensionObject(attribute, form))
        {
            plan.itemsKept = true;
        }
        else if (info != null)
        {
            Set<DataItem> removed = new LinkedHashSet<>();
            new FormItemVisitor(newCollector(removed, info.getDataPath(ScriptVariant.ENGLISH), form)).visit(form);
            plan.bound.addAll(removed);
        }
        if (info != null && !(attribute instanceof FormAttribute))
        {
            // FormAttributeService.removeAllColumnsOfAttribute(column, true): the nearest FormAttribute
            // above the column drops every additional-column entry whose tablePath starts with its path.
            AbstractDataPath path = info.getDataPath(ScriptVariant.ENGLISH);
            FormAttribute parent = owningAttribute(attribute);
            if (parent != null)
            {
                for (FormAttributeAdditionalColumns entry : parent.getAdditionalColumns())
                {
                    if (DatapathUtil.startsWith(entry.getTablePath(), path))
                    {
                        plan.additionalColumns.add(entry);
                    }
                }
            }
        }
        Detached detached = detachedWith(attribute);
        if (detached != null)
        {
            plan.detached.add(detached);
        }
        return plan;
    }

    /**
     * The object EDT detaches with the attribute: only a {@code FormAttribute}'s extInfo, through the
     * first matching factory, and only a non-proxy {@code IBmObject}
     * ({@code AbstractDefaultObjectFactory.detachObject}).
     */
    private static Detached detachedWith(AbstractFormAttribute attribute)
    {
        if (!(attribute instanceof FormAttribute))
        {
            return null;
        }
        FormAttributeExtInfo extInfo = ((FormAttribute)attribute).getExtInfo();
        if (extInfo == null)
        {
            return null;
        }
        for (EReference feature : DETACHED_EXT_INFO_FEATURES)
        {
            if (feature.getEContainingClass().isInstance(extInfo))
            {
                Object value = extInfo.eGet(feature);
                return value instanceof IBmObject && !((EObject)value).eIsProxy()
                    ? new Detached(extInfo.eClass().getName(), feature.getName(), (EObject)value) : null;
            }
        }
        return null;
    }

    private static FormAttribute owningAttribute(EObject column)
    {
        for (EObject up = column.eContainer(); up != null; up = up.eContainer())
        {
            if (up instanceof FormAttribute)
            {
                return (FormAttribute)up;
            }
        }
        return null;
    }

    private IFormItemCommand newCollector(Set<DataItem> result, AbstractDataPath prefix, Form form)
    {
        try
        {
            return (IFormItemCommand)collector.newInstance(result, prefix, associations, form);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            throw new IllegalStateException("EDT's form-item collector could not be created", e); //$NON-NLS-1$
        }
    }

    /**
     * The workspace file EDT stores an ATTACHED top object in, from EDT's own {@code IResourceLookup}.
     * Read before a detach: the export skips an FQN whose object is gone, so the file is the caller's
     * to remove.
     *
     * @param topObject an attached BM top object
     * @return its file, or {@code null} when it is not attached or the lookup is unavailable
     */
    public static IFile fileOf(EObject topObject)
    {
        if (!(topObject instanceof IBmObject) || ((IBmObject)topObject).bmGetEngine() == null)
        {
            return null;
        }
        try
        {
            IResourceLookup lookup = ServiceAccess.get(IResourceLookup.class);
            return lookup == null ? null : lookup.getPlatformResource(topObject);
        }
        catch (RuntimeException e)
        {
            Activator.logError("delete_metadata: EDT's resource lookup failed for a detached extInfo object", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Whether a BM object is out of the model: {@code TransactionService.doDetachObject} clears the
     * namespace of the object it detaches.
     *
     * @param object the object to inspect
     * @return {@code true} when it is not in a BM namespace
     */
    public static boolean isDetached(EObject object)
    {
        return !(object instanceof IBmObject) || ((IBmObject)object).bmGetNamespace() == null;
    }

    /**
     * The dotted data path of a data item, or {@code ""} when it is not one or carries none.
     *
     * @param item any form element
     * @return the path
     */
    public static String pathOf(EObject item)
    {
        if (!(item instanceof DataItem) || ((DataItem)item).getDataPath() == null)
        {
            return ""; //$NON-NLS-1$
        }
        return String.join(".", ((DataItem)item).getDataPath().getSegments()); //$NON-NLS-1$
    }
}
