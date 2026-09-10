/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormModelValidator;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Structural validation of one managed form (issue #473).
 *
 * <p>Read-only: it computes the findings from the model as it stands right now, which is the
 * difference between this tool and {@code get_project_errors} - that one reports the markers EDT
 * computed EARLIER, so a verdict right after an edit can be stale. The engine itself is
 * {@link FormModelValidator}, so a form-mutating tool can run the same checks inside its write
 * transaction before commit.</p>
 */
public class ValidateFormModelTool implements IMcpTool
{
    public static final String NAME = "validate_form_model"; //$NON-NLS-1$

    private static final String PARAM_FORM_FQN = "formFqn"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Check ONE managed form for structural defects: duplicate names or ids, a data path " //$NON-NLS-1$
            + "that names no attribute, a button with no command, a handler with no procedure or no " //$NON-NLS-1$
            + "event, a missing type-specific extInfo, more than one main attribute. Computed from " //$NON-NLS-1$
            + "the model NOW, so it is current right after an edit - unlike get_project_errors, " //$NON-NLS-1$
            + "which reports what EDT validated earlier. Changes nothing. Full parameters and " //$NON-NLS-1$
            + "examples: call get_tool_guide('validate_form_model')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty(PARAM_FORM_FQN,
                "FQN of the form to validate (required), as 'Type.Object.Form.FormName' (e.g. " //$NON-NLS-1$
                    + "'Catalog.Goods.Form.ItemForm') or 'CommonForm.Name'.", //$NON-NLS-1$
                true)
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the validation ran", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("valid", "True when no finding has severity 'error'.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("formPath", "The normalized FQN of the form that was validated.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("errors", "Number of findings with severity 'error'.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("warnings", "Number of findings with severity 'warning'.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("findings", //$NON-NLS-1$
                "One entry per defect: 'severity' (error/warning), 'code' (a stable kebab-case " //$NON-NLS-1$
                    + "identifier), 'path' (where it is, e.g. 'Field.Description' or '(form)' - usually " //$NON-NLS-1$
                    + "an address other form tools accept, but a duplicate name and the kinds with no " //$NON-NLS-1$
                    + "address of their own are locations only) and 'message'.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, PARAM_FORM_FQN);
        if (missing != null)
        {
            return missing;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String formFqn = JsonUtils.extractStringArgument(params, PARAM_FORM_FQN);

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveMetadataRoot(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(formFqn);
        // The address a caller writes is Type.Object.Form.Name; the resolver wants the .forms. shape,
        // and this is the one place that knows the difference.
        String formPath = FormElementWriter.parseFormPath(normFqn);
        MdObject mdForm =
            formPath == null ? null : FormStructureReader.resolveMdForm(resolved.scope(), formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found: '" + formFqn //$NON-NLS-1$
                + "'. Address a form as 'Type.Object.Form.FormName' or 'CommonForm.Name'; " //$NON-NLS-1$
                + "get_metadata_objects lists the forms an object owns.").toJson(); //$NON-NLS-1$
        }

        try
        {
            List<FormModelValidator.Finding> findings = FormElementWriter.readEditableForm(
                FormElementWriter.editContextFor(resolved.project(), mdForm), "ValidateFormModel", //$NON-NLS-1$
                (formModel, tx) -> FormModelValidator.validate(formModel));
            return report(normFqn, findings);
        }
        catch (Exception e)
        {
            // A form with no editable content model refuses through this exception, and its text
            // already names the form and the three reasons - returning it beats a generic wrapper.
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            return ToolResult.error("Could not validate '" + normFqn + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())).toJson();
        }
    }

    /** The findings, plus the two counts a caller branches on before reading any of them. */
    private static String report(String normFqn, List<FormModelValidator.Finding> findings)
    {
        List<Map<String, String>> rows = new ArrayList<>();
        int errors = 0;
        for (FormModelValidator.Finding finding : findings)
        {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("severity", finding.severity); //$NON-NLS-1$
            row.put("code", finding.code); //$NON-NLS-1$
            row.put("path", finding.path); //$NON-NLS-1$
            row.put("message", finding.message); //$NON-NLS-1$
            rows.add(row);
            if (FormModelValidator.SEVERITY_ERROR.equals(finding.severity))
            {
                errors++;
            }
        }
        return ToolResult.success()
            .put("valid", errors == 0) //$NON-NLS-1$
            .put("formPath", normFqn) //$NON-NLS-1$
            .put("errors", errors) //$NON-NLS-1$
            .put("warnings", findings.size() - errors) //$NON-NLS-1$
            .put("findings", rows) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, errors == 0
                ? "No structural errors in " + normFqn + "." //$NON-NLS-1$ //$NON-NLS-2$
                : errors + " structural error(s) in " + normFqn + ".") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }
}
