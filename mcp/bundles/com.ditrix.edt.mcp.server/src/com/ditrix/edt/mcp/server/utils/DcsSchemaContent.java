/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.UUID;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcorePackage;
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
        return resolveServices(model, generator, factory, project);
    }

    /** Package-visible readiness seam: a V8 project is not usable until its version is available. */
    static Services resolveServices(IBmModel model, ITopObjectFqnGenerator generator,
        IModelObjectFactory factory, IV8Project project)
    {
        Version version = project == null ? null : project.getVersion();
        if (model == null || generator == null || factory == null || project == null || version == null)
        {
            return Services.failure("EDT services needed to materialize DCS content are unavailable " //$NON-NLS-1$
                + "(BM model, external-property FQN generator, model factory, V8 project, or " //$NON-NLS-1$
                + "platform version). Wait for EDT to finish loading the project, then retry."); //$NON-NLS-1$
        }
        return Services.success(model, generator, factory, version);
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
            Long ownerId = target.bmId(BmRole.ROOT_OWNER);
            EObject object = ownerId == null ? null : transaction.getObjectById(ownerId.longValue());
            String ownerType = DcsMainSchemaOwner.expectedType(target.normalizedRootFqn());
            if (!DcsMainSchemaOwner.supports(object))
            {
                return ResolveResult.failure(ownerType + " DCS target '" //$NON-NLS-1$
                    + target.normalizedRootFqn() + "' disappeared before the write transaction. " //$NON-NLS-1$
                    + "Re-run dcs action='get'."); //$NON-NLS-1$
            }
            template = findOrCreateMainTemplate(object, services.factory, services.version);
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

    private static BasicTemplate findOrCreateMainTemplate(EObject owner, IModelObjectFactory factory,
        Version version)
    {
        BasicTemplate existing = DcsMainSchemaOwner.get(owner);
        if (existing != null)
        {
            return existing;
        }
        MdObject child = (MdObject)factory.create(MdClassPackage.Literals.TEMPLATE,
            (MdObject)owner, version);
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
        DcsMainSchemaOwner.addAndSet(owner, template);
        factory.fillDefaultReferences(template);
        return template;
    }

    static ResolveResult resolveTemplateContent(IBmTransaction transaction, BasicTemplate template,
        ITopObjectFqnGenerator generator, String fqn)
    {
        EObject content = template.getTemplate();
        // The tests the reader shares: an attached schema is the one written to (isWrittenSchema); a detached
        // schema that holds content is refused (unattachedContentRefusal); an empty detached one falls through
        // to be replaced by a fresh attached one below.
        if (isWrittenSchema(content))
        {
            return ResolveResult.success((DataCompositionSchema)content, attachedFqn(content));
        }
        String unattached = unattachedContentRefusal(content, fqn);
        if (unattached != null)
        {
            return ResolveResult.failure(unattached);
        }
        // A template DECLARED as a DCS whose content resource does not exist yet answers with an
        // unresolved placeholder (eClass degrades to the bare EObject), not null - that is an empty
        // schema to materialize below, not foreign content.
        if (content != null && !(content instanceof DataCompositionSchema)
            && (content.eIsProxy() || content.eClass() == EcorePackage.Literals.EOBJECT))
        {
            content = null;
        }
        if (content != null && !(content instanceof DataCompositionSchema))
        {
            return ResolveResult.failure("Template '" + fqn + "' now contains '" //$NON-NLS-1$ //$NON-NLS-2$
                + content.eClass().getName() + "', not DataCompositionSchema. Re-save it as a DCS " //$NON-NLS-1$
                + "template and retry."); //$NON-NLS-1$
        }
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        template.setTemplate(schema);
        String contentFqn = generator == null ? null : generator.generateExternalPropertyFqn(template,
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

    /**
     * Whether a template's content is the schema a write mutates: a {@link DataCompositionSchema} attached to
     * the model. No content, an unresolved placeholder or an EMPTY detached schema is replaced by
     * {@link #resolveTemplateContent} with a fresh attached schema, so the reader that computes the write's hash
     * treats it as no schema yet ({@code DcsRootReader}); a detached schema holding content is refused by both
     * ({@link #unattachedContentRefusal}). One test for both sides keeps the hash on the object the write then
     * changes (#662).
     *
     * @param content a template's content, may be {@code null}
     * @return whether the write would mutate this very object
     */
    public static boolean isWrittenSchema(EObject content)
    {
        return content instanceof DataCompositionSchema && attachedFqn(content) != null;
    }

    /**
     * The refusal for a template whose content is a schema that is not attached to the model and either holds
     * content or is an unresolved reference to a stored schema: the write cannot change it (it is not the stored schema) and must not replace it with an empty one
     * (that would drop what it holds), so the reader and the resolver both refuse it. An EMPTY detached schema
     * is no content and is replaced like a missing one.
     *
     * @param content a template's content, may be {@code null}
     * @param fqn the root, for the message
     * @return the refusal, or {@code null} when the content is attached, absent or an empty detached schema
     */
    public static String unattachedContentRefusal(EObject content, String fqn)
    {
        // An unresolved schema reference shows no content of its own but stands for a stored schema: not empty.
        if (!(content instanceof DataCompositionSchema) || isWrittenSchema(content)
            || !content.eIsProxy() && isEmpty(content))
        {
            return null;
        }
        return "The DCS content of '" + fqn + "' is a schema that is not attached to the EDT model" //$NON-NLS-1$ //$NON-NLS-2$
            + (content.eIsProxy() ? " (an unresolved reference)" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + ", so it can be neither read for a hash nor written; nothing was written. Save any unsaved edits " //$NON-NLS-1$
            + "in EDT first (clean_project discards them), then re-open or clean the project and re-run dcs " //$NON-NLS-1$
            + "action='get'."; //$NON-NLS-1$
    }

    private static boolean isEmpty(EObject content)
    {
        for (EStructuralFeature feature : content.eClass().getEAllStructuralFeatures())
        {
            if (!feature.isDerived() && !feature.isTransient() && content.eIsSet(feature))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The object's BM FQN when it is an ATTACHED BM object, else {@code null}.
     *
     * @param object any model object, may be {@code null}
     * @return the FQN, or {@code null}
     */
    public static String attachedFqn(EObject object)
    {
        return object instanceof IBmObject ? attachedFqnOrNull((IBmObject)object) : null;
    }

    /**
     * The object's BM FQN when it is ATTACHED, or {@code null} when it is not. {@code bmGetFqn()} is
     * legal only on an attached top object and throws otherwise, and {@link IBmObject} offers no
     * attachment predicate, so the call itself is the only reliable test.
     */
    private static String attachedFqnOrNull(IBmObject object)
    {
        if (!object.bmIsTop())
        {
            return null;
        }
        try
        {
            String fqn = object.bmGetFqn();
            return fqn == null || fqn.isEmpty() ? null : fqn;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
