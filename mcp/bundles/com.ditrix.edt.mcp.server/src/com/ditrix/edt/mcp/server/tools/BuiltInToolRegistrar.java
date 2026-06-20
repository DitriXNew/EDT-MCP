/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.impl.AdoptMetadataObjectTool;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool;
import com.ditrix.edt.mcp.server.tools.impl.CreateInfobaseTool;
import com.ditrix.edt.mcp.server.tools.impl.SetInfobaseCredentialsTool;
import com.ditrix.edt.mcp.server.tools.impl.CreateLaunchConfigTool;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.CreateProjectTool;
import com.ditrix.edt.mcp.server.tools.impl.DebugLaunchTool;
import com.ditrix.edt.mcp.server.tools.impl.DebugStatusTool;
import com.ditrix.edt.mcp.server.tools.impl.DebugYaxunitTestsTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteInfobaseTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteLaunchConfigTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteProjectTool;
import com.ditrix.edt.mcp.server.tools.impl.EnableToolsetTool;
import com.ditrix.edt.mcp.server.tools.impl.EvaluateExpressionTool;
import com.ditrix.edt.mcp.server.tools.impl.ExportConfigurationToXmlTool;
import com.ditrix.edt.mcp.server.tools.impl.FindReferencesTool;
import com.ditrix.edt.mcp.server.tools.impl.GenerateTranslationStringsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetApplicationsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetCheckDescriptionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetConfigurationPropertiesTool;
import com.ditrix.edt.mcp.server.tools.impl.GetContentAssistTool;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetFormLayoutSnapshotTool;
import com.ditrix.edt.mcp.server.tools.impl.GetFormScreenshotTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMarkersTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMetadataDetailsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMetadataObjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMethodCallHierarchyTool;
import com.ditrix.edt.mcp.server.tools.impl.GetModuleStructureTool;
import com.ditrix.edt.mcp.server.tools.impl.GetObjectsByTagsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetPlatformDocumentationTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProblemSummaryTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetServerStatusTool;
import com.ditrix.edt.mcp.server.tools.impl.GetSubsystemContentTool;
import com.ditrix.edt.mcp.server.tools.impl.GetSymbolInfoTool;
import com.ditrix.edt.mcp.server.tools.impl.GetTagsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetToolGuideTool;
import com.ditrix.edt.mcp.server.tools.impl.GetTranslationProjectInfoTool;
import com.ditrix.edt.mcp.server.tools.impl.GetVariablesTool;
import com.ditrix.edt.mcp.server.tools.impl.GoToDefinitionTool;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool;
import com.ditrix.edt.mcp.server.tools.impl.ListBreakpointsTool;
import com.ditrix.edt.mcp.server.tools.impl.ListConfigurationsTool;
import com.ditrix.edt.mcp.server.tools.impl.ListModulesTool;
import com.ditrix.edt.mcp.server.tools.impl.ListProjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.ListSubsystemsTool;
import com.ditrix.edt.mcp.server.tools.impl.ListToolsetsTool;
import com.ditrix.edt.mcp.server.tools.impl.ReadMethodSourceTool;
import com.ditrix.edt.mcp.server.tools.impl.ReadModuleSourceTool;
import com.ditrix.edt.mcp.server.tools.impl.RemoveBreakpointTool;
import com.ditrix.edt.mcp.server.tools.impl.RenameMetadataObjectTool;
import com.ditrix.edt.mcp.server.tools.impl.ResumeTool;
import com.ditrix.edt.mcp.server.tools.impl.ResyncToDiskTool;
import com.ditrix.edt.mcp.server.tools.impl.RevalidateObjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.RunYaxunitTestsTool;
import com.ditrix.edt.mcp.server.tools.impl.SearchInCodeTool;
import com.ditrix.edt.mcp.server.tools.impl.SetBreakpointTool;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.StartProfilingTool;
import com.ditrix.edt.mcp.server.tools.impl.StepTool;
import com.ditrix.edt.mcp.server.tools.impl.StopProfilingTool;
import com.ditrix.edt.mcp.server.tools.impl.TerminateLaunchTool;
import com.ditrix.edt.mcp.server.tools.impl.TranslateConfigurationTool;
import com.ditrix.edt.mcp.server.tools.impl.UpdateDatabaseTool;
import com.ditrix.edt.mcp.server.tools.impl.ValidateQueryTool;
import com.ditrix.edt.mcp.server.tools.impl.WaitForBreakTool;
import com.ditrix.edt.mcp.server.tools.impl.WriteModuleSourceTool;

/**
 * Registers all built-in MCP tools into an {@link McpToolRegistry}.
 * <p>
 * Extracted from the HTTP transport ({@code McpServer}) so that the catalogue of
 * tools is no longer coupled to the transport: adding a tool touches only this
 * class, and the registry can be populated (and tested) without instantiating
 * the server. {@code McpServer.registerTools()} and {@code Activator} both
 * delegate here.
 */
public final class BuiltInToolRegistrar
{
    private BuiltInToolRegistrar()
    {
        // Utility class
    }

    /**
     * Clears the registry and registers every built-in tool.
     *
     * @param registry the registry to populate (cleared first)
     */
    public static void registerAll(McpToolRegistry registry)
    {
        // Clear existing tools
        registry.clear();

        // Register built-in tools
        registry.register(new GetEdtVersionTool());
        registry.register(new GetServerStatusTool());
        registry.register(new GetToolGuideTool());
        // Progressive tool disclosure meta-tools (core toolset)
        registry.register(new ListToolsetsTool());
        registry.register(new EnableToolsetTool());
        registry.register(new ListProjectsTool());
        registry.register(new GetConfigurationPropertiesTool());
        registry.register(new CleanProjectTool());
        registry.register(new RevalidateObjectsTool());
        registry.register(new ResyncToDiskTool());
        registry.register(new ExportConfigurationToXmlTool());
        registry.register(new ImportConfigurationFromXmlTool());
        registry.register(new DeleteProjectTool());
        registry.register(new CreateProjectTool());
        registry.register(new GetProblemSummaryTool());
        registry.register(new GetProjectErrorsTool());
        registry.register(new GetMarkersTool());
        registry.register(new GetCheckDescriptionTool());
        registry.register(new GetContentAssistTool());
        registry.register(new GetPlatformDocumentationTool());
        registry.register(new GetMetadataObjectsTool());
        registry.register(new GetMetadataDetailsTool());
        registry.register(new ListSubsystemsTool());
        registry.register(new GetSubsystemContentTool());
        registry.register(new FindReferencesTool());

        // Tag tools
        registry.register(new GetTagsTool());
        registry.register(new GetObjectsByTagsTool());

        // Application tools
        registry.register(new GetApplicationsTool());
        registry.register(new CreateInfobaseTool());
        registry.register(new SetInfobaseCredentialsTool());
        registry.register(new DeleteInfobaseTool());
        registry.register(new UpdateDatabaseTool());
        registry.register(new DebugLaunchTool());
        registry.register(new ListConfigurationsTool());
        registry.register(new CreateLaunchConfigTool());
        registry.register(new DeleteLaunchConfigTool());
        registry.register(new RunYaxunitTestsTool());
        registry.register(new TerminateLaunchTool());

        // Debug inspection tools (breakpoints + suspended state)
        registry.register(new SetBreakpointTool());
        registry.register(new RemoveBreakpointTool());
        registry.register(new ListBreakpointsTool());
        registry.register(new WaitForBreakTool());
        registry.register(new GetVariablesTool());
        registry.register(new StepTool());
        registry.register(new ResumeTool());
        registry.register(new EvaluateExpressionTool());
        registry.register(new DebugYaxunitTestsTool()); // NOSONAR deprecated EDT API used intentionally (no non-deprecated equivalent here)
        registry.register(new DebugStatusTool());
        registry.register(new StartProfilingTool());
        registry.register(new StopProfilingTool());
        registry.register(new GetProfilingResultsTool());

        // BSL code analysis tools
        registry.register(new ReadModuleSourceTool());
        registry.register(new WriteModuleSourceTool());
        registry.register(new GetModuleStructureTool());
        registry.register(new ListModulesTool());
        registry.register(new SearchInCodeTool());
        registry.register(new ReadMethodSourceTool());
        registry.register(new GetMethodCallHierarchyTool());
        registry.register(new GoToDefinitionTool());
        registry.register(new GetSymbolInfoTool());
        registry.register(new GetFormLayoutSnapshotTool());
        registry.register(new GetFormScreenshotTool());
        registry.register(new ValidateQueryTool());

        // Metadata refactoring tools (form members are created/edited/removed by their FQNs via
        // create/modify/delete_metadata; the former add_form_*/set_form_item_property/delete_form_item/
        // get_form_structure tools were folded into those + get_metadata_details and removed in F4b).
        registry.register(new RenameMetadataObjectTool());
        registry.register(new DeleteMetadataTool());
        registry.register(new CreateMetadataTool());
        registry.register(new ModifyMetadataTool());
        registry.register(new AdoptMetadataObjectTool());

        // LanguageTool translation tools
        registry.register(new GenerateTranslationStringsTool());
        registry.register(new TranslateConfigurationTool());
        registry.register(new GetTranslationProjectInfoTool());

        Activator.logInfo("Registered " + registry.getToolCount() + " MCP tools"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
