/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;

/** Type-safe access to the main DCS member shared only by reports and external reports. */
public final class DcsMainSchemaOwner
{
    private DcsMainSchemaOwner()
    {
        // utility class
    }

    /** Whether {@code object} is a Report or an ExternalReport, the only owners of a main DCS. */
    public static boolean supports(EObject object)
    {
        return object instanceof Report || object instanceof ExternalReport;
    }

    /**
     * The owner's {@code mainDataCompositionSchema} feature, or {@code null} when {@code object} owns no main DCS.
     * Report and ExternalReport declare it separately (MdClass.xcore), so the literal follows the owner's class.
     */
    public static EStructuralFeature feature(EObject object)
    {
        if (object instanceof Report)
        {
            return MdClassPackage.Literals.REPORT__MAIN_DATA_COMPOSITION_SCHEMA;
        }
        if (object instanceof ExternalReport)
        {
            return MdClassPackage.Literals.EXTERNAL_REPORT__MAIN_DATA_COMPOSITION_SCHEMA;
        }
        return null;
    }

    /**
     * Makes {@code template}, already one of the owner's templates, the owner's main data composition schema -
     * what EDT's report DCS wizard does after it adds the template ({@code TemplateWizard.setMainDataCompositionSchema}).
     * Runs inside the caller's write transaction.
     */
    public static void setMain(EObject object, BasicTemplate template)
    {
        if (object instanceof Report)
        {
            ((Report)object).setMainDataCompositionSchema(template);
            return;
        }
        if (object instanceof ExternalReport)
        {
            ((ExternalReport)object).setMainDataCompositionSchema(template);
            return;
        }
        throw new IllegalArgumentException("Only Report and ExternalReport can own a main DCS"); //$NON-NLS-1$
    }

    static BasicTemplate get(EObject object)
    {
        if (object instanceof Report)
        {
            return ((Report)object).getMainDataCompositionSchema();
        }
        if (object instanceof ExternalReport)
        {
            return ((ExternalReport)object).getMainDataCompositionSchema();
        }
        return null;
    }

    static void addAndSet(EObject object, Template template)
    {
        if (object instanceof Report)
        {
            Report report = (Report)object;
            report.getTemplates().add(template);
            report.setMainDataCompositionSchema(template);
            return;
        }
        if (object instanceof ExternalReport)
        {
            ExternalReport report = (ExternalReport)object;
            report.getTemplates().add(template);
            report.setMainDataCompositionSchema(template);
            return;
        }
        throw new IllegalArgumentException("Only Report and ExternalReport can own a main DCS"); //$NON-NLS-1$
    }

    static String expectedType(String rootFqn)
    {
        if (rootFqn != null)
        {
            int separator = rootFqn.indexOf('.');
            String rawType = separator < 0 ? rootFqn : rootFqn.substring(0, separator);
            String englishType = MetadataTypeUtils.toEnglishSingular(rawType);
            if ("Report".equals(englishType) || "ExternalReport".equals(englishType)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return englishType;
            }
        }
        return "Report or ExternalReport"; //$NON-NLS-1$
    }
}
