/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.BmRole;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.Target;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/** Shared materialization of external DCS content for every schema-root write path. */
public final class DcsSchemaContent
{
    private static final String DEFAULT_DCS_TEMPLATE_NAME =
        "\u041E\u0441\u043D\u043E\u0432\u043D\u0430\u044F\u0421\u0445\u0435\u043C\u0430\u041A\u043E" //$NON-NLS-1$
            + "\u043C\u043F\u043E\u043D\u043E\u0432\u043A\u0438\u0414\u0430\u043D\u043D\u044B\u0445"; //$NON-NLS-1$

    private DcsSchemaContent()
    {
        // Utility class
    }

    /** Resolves services needed to create/attach DCS content. */
    public static Services resolveServices(ProjectContext.ConfigurationResult context, IBmModel model)
    {
        if (context == null || !context.ok() || model == null)
        {
            return Services.failure("The project or BM model is unavailable for the DCS write. " //$NON-NLS-1$
                + "Wait for EDT to finish loading the project, then retry."); //$NON-NLS-1$
        }
        ITopObjectFqnGenerator generator = Activator.getDefault().getTopObjectFqnGenerator();
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        IV8ProjectManager manager = Activator.getDefault().getV8ProjectManager();
        IV8Project project = manager == null ? null : manager.getProject(context.project());
        if (generator == null || factory == null || project == null)
        {
            return Services.failure("EDT services needed to materialize DCS content are unavailable " //$NON-NLS-1$
                + "(external-property FQN generator, model factory, or V8 project). Re-open the " //$NON-NLS-1$
                + "project and retry."); //$NON-NLS-1$
        }
        return Services.success(model, generator, factory, project.getVersion());
    }

    /** Re-fetches/materializes one schema inside the caller's active write transaction. */
    public static ResolveResult resolve(IBmTransaction transaction, Target target, Services services)
    {
        if (transaction == null || target == null || services == null || services.error != null)
        {
            return ResolveResult.failure("The resolved DCS write target is unavailable. Re-run dcs " //$NON-NLS-1$
                + "action='get', then retry the write."); //$NON-NLS-1$
        }
        BasicTemplate template;
        if (target.kind() == TargetKind.REPORT_MAIN_DCS)
        {
            Long reportId = target.bmId(BmRole.ROOT_OWNER);
            EObject object = reportId == null ? null : transaction.getObjectById(reportId.longValue());
            if (!(object instanceof Report))
            {
                return ResolveResult.failure("Report DCS target '" + target.normalizedRootFqn() //$NON-NLS-1$
                    + "' disappeared before the write transaction. Re-run dcs action='get'."); //$NON-NLS-1$
            }
            template = findOrCreateMainTemplate((Report)object, services.factory, services.version);
        }
        else
        {
            Long templateId = target.bmId(BmRole.TEMPLATE);
            EObject object = templateId == null ? null : transaction.getObjectById(templateId.longValue());
            if (!(object instanceof BasicTemplate))
            {
                return ResolveResult.failure("DCS template '" + target.normalizedRootFqn() //$NON-NLS-1$
                    + "' disappeared before the write transaction. Re-run dcs action='get'."); //$NON-NLS-1$
            }
            template = (BasicTemplate)object;
        }
        return resolveTemplateContent(transaction, template, services.generator,
            target.normalizedRootFqn());
    }

    /** Shared legacy/report entry used by {@code modify_metadata.dcs}. */
    public static ResolveResult resolveReport(IBmTransaction transaction, Report report,
        IModelObjectFactory factory, Version version, ITopObjectFqnGenerator generator, String fqn)
    {
        BasicTemplate template = findOrCreateMainTemplate(report, factory, version);
        return resolveTemplateContent(transaction, template, generator, fqn);
    }

    private static BasicTemplate findOrCreateMainTemplate(Report report, IModelObjectFactory factory,
        Version version)
    {
        BasicTemplate existing = report.getMainDataCompositionSchema();
        if (existing != null)
        {
            return existing;
        }
        MdObject child = (MdObject)factory.create(MdClassPackage.Literals.TEMPLATE, report, version);
        if (child == null)
        {
            child = (MdObject)EcoreUtil.create(MdClassPackage.Literals.TEMPLATE);
        }
        Template template = (Template)child;
        template.setName(DEFAULT_DCS_TEMPLATE_NAME);
        if (template.getUuid() == null)
        {
            template.setUuid(UUID.randomUUID());
        }
        template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
        report.getTemplates().add(template);
        report.setMainDataCompositionSchema(template);
        factory.fillDefaultReferences(template);
        return template;
    }

    private static ResolveResult resolveTemplateContent(IBmTransaction transaction, BasicTemplate template,
        ITopObjectFqnGenerator generator, String fqn)
    {
        EObject content = template.getTemplate();
        if (content instanceof DataCompositionSchema && content instanceof IBmObject
            && ((IBmObject)content).bmIsTop())
        {
            return ResolveResult.success((DataCompositionSchema)content,
                ((IBmObject)content).bmGetFqn());
        }
        if (content != null && !(content instanceof DataCompositionSchema))
        {
            return ResolveResult.failure("Template '" + fqn + "' now contains '" //$NON-NLS-1$ //$NON-NLS-2$
                + content.eClass().getName() + "', not DataCompositionSchema. Re-save it as a DCS " //$NON-NLS-1$
                + "template and retry."); //$NON-NLS-1$
        }
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        template.setTemplate(schema);
        String contentFqn = generator.generateExternalPropertyFqn(template,
            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            return ResolveResult.failure("Could not generate the external DCS content FQN for '" //$NON-NLS-1$
                + fqn + "'. Re-open and save the template in EDT, then retry."); //$NON-NLS-1$
        }
        transaction.attachTopObject((IBmObject)schema, contentFqn);
        return ResolveResult.success(schema, contentFqn);
    }

    /** Resolved immutable write services. */
    public static final class Services
    {
        private final IBmModel model;
        private final ITopObjectFqnGenerator generator;
        private final IModelObjectFactory factory;
        private final Version version;
        private final String error;

        private Services(IBmModel model, ITopObjectFqnGenerator generator, IModelObjectFactory factory,
            Version version, String error)
        {
            this.model = model;
            this.generator = generator;
            this.factory = factory;
            this.version = version;
            this.error = error;
        }

        private static Services success(IBmModel model, ITopObjectFqnGenerator generator,
            IModelObjectFactory factory, Version version)
        {
            return new Services(model, generator, factory, version, null);
        }

        private static Services failure(String error)
        {
            return new Services(null, null, null, null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public IBmModel model()
        {
            return model;
        }

        public Version version()
        {
            return version;
        }

        public String error()
        {
            return error;
        }
    }

    /** Transaction-local schema plus its external-resource export FQN. */
    public static final class ResolveResult
    {
        private final DataCompositionSchema schema;
        private final String contentFqn;
        private final String error;

        private ResolveResult(DataCompositionSchema schema, String contentFqn, String error)
        {
            this.schema = schema;
            this.contentFqn = contentFqn;
            this.error = error;
        }

        private static ResolveResult success(DataCompositionSchema schema, String contentFqn)
        {
            return new ResolveResult(schema, contentFqn, null);
        }

        private static ResolveResult failure(String error)
        {
            return new ResolveResult(null, null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public DataCompositionSchema schema()
        {
            return schema;
        }

        public String contentFqn()
        {
            return contentFqn;
        }

        public String error()
        {
            return error;
        }
    }
}
