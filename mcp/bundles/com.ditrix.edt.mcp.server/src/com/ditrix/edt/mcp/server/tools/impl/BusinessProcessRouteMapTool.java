/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeConnection;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeFigure;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.utils.ExtensionServices;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.ditrix.edt.mcp.server.utils.routemap.RouteMapReader;
import com.ditrix.edt.mcp.server.utils.routemap.RouteMapRequest;
import com.ditrix.edt.mcp.server.utils.routemap.RouteMapWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reads and replaces the separate BM top object behind BusinessProcess.flowchart. */
public class BusinessProcessRouteMapTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "business_process_route_map";

    @FunctionalInterface
    interface ConsentRequester
    {
        ConsentDecision request(String tool, ConsentPreview preview);
    }

    private final ConsentRequester consent;

    public BusinessProcessRouteMapTool()
    {
        this((tool, preview) -> DestructiveConsentGate.getInstance().requireConsent(tool, preview));
    }

    BusinessProcessRouteMapTool(ConsentRequester consent)
    {
        this.consent = consent;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read, replace or remove a BusinessProcess route map. Call action='get' before replacing; "
            + "replace discards all existing items, preserving point identity only for same-named points. "
            + "Replacing a non-empty map and removing an existing map require destructive consent. "
            + "Full parameters and examples: call get_tool_guide('business_process_route_map').";
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        return ToolAnnotationClassifier.classify(NAME);
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name.", true)
            .stringProperty("fqn", "BusinessProcess.Name; only the TYPE token may be Russian/English; "
                + "the object name is a programmatic identifier, not a synonym.", true)
            .enumProperty("action", "Operation; replace needs points and connections, even when empty.",
                true, "get", "replace", "remove")
            .objectArrayProperty("points", "Replacement points: {name,type,title?,x?,y?,width?,height?,"
                + "taskDescription?,group?}; omitted sizes use platform defaults.")
            .objectArrayProperty("connections", "Replacement connections: {from,to,name?,title?,fromPort?,"
                + "toPort?,branch?}; Condition sources require branch='true' or 'false'.")
            .stringProperty("language", "Configuration language code for point and connection titles.")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        JsonObject schema = JsonParser.parseString(WrittenObjectMarkers.declareOutput(JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded.", true)
            .stringProperty("error", "Actionable refusal on failure.")
            .stringProperty("message", "Operation and validation status.")
            .enumProperty("action", "Requested operation.", "get", "replace", "remove")
            .stringProperty("fqn", "Canonical BusinessProcess FQN.")
            .stringProperty("routeMapFqn", "External-property BM top-object FQN.")
            .stringProperty("file", "Route-map path from EDT's project filesystem service.")
            .booleanProperty("routeMapExists", "Whether the route-map top object exists.")
            .objectProperty("defaults", "Platform sizes per kind [width,height] and default ports.")
            .stringProperty("language", "Resolved title language code, when configured.")
            .booleanProperty("persisted", "Whether replacement export was submitted or removal was confirmed on disk.")
            .stringArrayProperty("writtenProjects", "Projects written by this call."))
            .build()).getAsJsonObject();
        for (String key : List.of("points", "connections"))
        {
            JsonObject union = JsonParser.parseString("{\"oneOf\":[{\"type\":\"integer\"},"
                + "{\"type\":\"array\",\"items\":{\"type\":\"object\"}}]}").getAsJsonObject();
            union.addProperty("description", "Items for get; written count for replace; discarded count for remove.");
            schema.getAsJsonObject("properties").add(key, union);
        }
        return GsonProvider.toJson(schema);
    }

    private record Inputs(String projectName, String fqn, String action, String language, RouteMapRequest request) { }

    private static Inputs inputs(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName");
        String fqn = JsonUtils.extractStringArgument(params, "fqn");
        String action = JsonUtils.extractStringArgument(params, "action");
        String points = JsonUtils.extractStringArgument(params, "points");
        String connections = JsonUtils.extractStringArgument(params, "connections");
        String language = JsonUtils.extractStringArgument(params, "language");
        String required = JsonUtils.requireArguments(params, "projectName", "fqn", "action");
        if (required != null)
        {
            throw Refusals.argument(JsonParser.parseString(required).getAsJsonObject()
                .get("error").getAsString());
        }
        if (!List.of("get", "replace", "remove").contains(action))
        {
            throw Refusals.argument("Unknown action '" + action + "'. Use get, replace or remove.");
        }
        if (!action.equals("replace") && (points != null || connections != null))
        {
            throw Refusals.argument("Action '" + action + "' does not accept points or connections. "
                + "Use action='replace' to write a route map.");
        }
        return new Inputs(projectName, MetadataTypeUtils.normalizeFqn(fqn), action, language,
            action.equals("replace") ? RouteMapRequest.parse(points, connections) : null);
    }

    @Override
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        try
        {
            inputs(params);
            return null;
        }
        catch (IllegalArgumentException e)
        {
            return e.getMessage();
        }
    }

    private record Snapshot(long ownerId, String ownerFqn, String schemeFqn, String file,
        String language, GraphicalScheme previous, JsonObject result) { }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        try
        {
            return executeRouteMap(inputs(params));
        }
        catch (RuntimeException e)
        {
            String refusal = Refusals.messageOf(e);
            if (refusal != null)
            {
                Refusals.log("Error in " + getName(), e); //$NON-NLS-1$
                return ToolResult.error(refusal).toJson();
            }
            throw e;
        }
    }

    private String executeRouteMap(Inputs args)
    {
        ProjectContext ctx = resolveProjectAndScope(args.projectName());
        if (ctx.hasError())
        {
            return ctx.error;
        }
        var plugin = Activator.getDefault();
        if (plugin == null || plugin.getBmModelManager() == null || plugin.getTopObjectFqnGenerator() == null
            || MdPlugin.getDefault() == null || MdPlugin.getDefault().getInjector() == null)
        {
            return ToolResult.error("EDT route-map services are unavailable. "
                + "Wait for project initialization, then retry.").toJson();
        }
        IBmModel model = plugin.getBmModelManager().getModel(ctx.project);
        if (model == null)
        {
            return ToolResult.error("BM model unavailable for '" + args.projectName()
                + "'. Refresh the project and retry.").toJson();
        }
        Snapshot snapshot = BmTransactions.read(model, "ReadBusinessProcessRouteMap", (tx, pm) -> {
            var node = MetadataNodeResolver.resolveExisting(ctx.scope, args.fqn());
            BusinessProcess owner = requireBusinessProcess(node == null ? null : node.object,
                node != null && node.topLevel, args.fqn());
            requireSupportedOwner(owner);
            if (!args.action().equals("get"))
            {
                String refusal = VendorSupportGuard.refusalForFqn(ctx.scope, args.fqn(),
                    VendorSupportGuard.Intent.MODIFY);
                if (refusal != null)
                {
                    throw Refusals.argument(refusal);
                }
            }
            Content content = readContent(tx, owner, Activator.getDefault().getTopObjectFqnGenerator());
            String schemeFqn = content.fqn();
            var filesystem = MdPlugin.getDefault().getInjector().getInstance(IProjectFileSystemSupportProvider.class)
                .getProjectFileSystemSupport(ctx.project);
            IPath file = filesystem == null ? null
                : filesystem.getPath(owner, MdClassPackage.Literals.BUSINESS_PROCESS__FLOWCHART);
            if (file == null)
            {
                throw Refusals.state("Cannot resolve the route-map file for '" + args.fqn()
                    + "'. Refresh the project and retry.");
            }
            GraphicalScheme existing = content.scheme();
            String language = MetadataLanguageUtils.resolveLanguageCode(ctx.config, args.language());
            if (args.request() != null)
            {
                boolean titles = args.request().points().stream().anyMatch(point -> point.title() != null)
                    || args.request().connections().stream().anyMatch(connection -> connection.title() != null);
                if (titles)
                {
                    language = MetadataLanguageUtils.resolveSynonymLanguage(ctx.config, "title", args.language(),
                        "route-map titles");
                }
            }
            JsonObject result = RouteMapReader.read(existing, language);
            result.addProperty("success", true);
            result.addProperty("action", args.action());
            result.addProperty("fqn", ((IBmObject)owner).bmGetFqn());
            result.addProperty("routeMapFqn", schemeFqn);
            result.addProperty("file", file.toPortableString());
            if (language != null)
            {
                result.addProperty("language", language);
            }
            return new Snapshot(((IBmObject)owner).bmGetId(), ((IBmObject)owner).bmGetFqn(), schemeFqn,
                file.toPortableString(), language,
                existing == null ? null : EcoreUtil.copy(existing), result);
        });
        if (args.action().equals("get"))
        {
            WriteScope.recordNothingQueued();
            return GsonProvider.toJson(snapshot.result());
        }
        MapPreview preview = mapPreview(snapshot.previous());
        return consentAndWrite(args.action(), snapshot.ownerFqn(), preview, () -> {
                BmTransactions.BmOperation<Void> mutation = (tx, pm) -> {
                    BusinessProcess owner = requireBusinessProcess((EObject)tx.getObjectById(snapshot.ownerId()),
                        true, snapshot.ownerFqn());
                    requireSupportedOwner(owner);
                    GraphicalScheme scheme = RouteMapWriter.existing(tx, snapshot.schemeFqn());
                    if (!EcoreUtil.equals(snapshot.previous(), scheme))
                    {
                        throw Refusals.argument("Route map of '" + snapshot.ownerFqn()
                            + "' changed during consent. Call action='get' and retry.");
                    }
                    if (args.action().equals("remove"))
                    {
                        tx.detachTopObject((IBmObject)scheme);
                    }
                    else
                    {
                        if (scheme == null)
                        {
                            var manager = Activator.getDefault().getV8ProjectManager();
                            var project = manager == null ? null : manager.getProject(ctx.project);
                            if (project == null)
                            {
                                throw Refusals.state("Cannot resolve the runtime version for '"
                                    + args.projectName() + "'. Refresh the project and retry.");
                            }
                            scheme = RouteMapWriter.attach(tx, snapshot.schemeFqn(), project.getVersion());
                        }
                        RouteMapWriter.replace(scheme, args.request(), snapshot.language(), owner);
                    }
                    return null;
                };
                applyMutation(model, args.action(), mutation);
                boolean persisted = false;
                if (args.action().equals("remove"))
                {
                    // The global commit deletes detached content; there is no scheme left to export.
                    // The owner's flowchart reference is transient, so its .mdo is unchanged.
                    WriteScope.recordWrite(ctx.project);
                }
                else
                {
                    persisted = BmTransactions.forceExportToDisk(ctx.project, snapshot.schemeFqn());
                }
                return writeResult(args.action(), preview, args.request()).put("fqn", snapshot.ownerFqn())
                    .put("routeMapFqn", snapshot.schemeFqn()).put("file", snapshot.file())
                    .put("persisted", persisted).toJson();
            });
    }

    record Content(String fqn, GraphicalScheme scheme) { }

    static void applyMutation(IBmModel model, String action, BmTransactions.BmOperation<Void> mutation)
    {
        if (action.equals("remove"))
        {
            // As with owned-form deletion, EDT's global commit schedules deletion of
            // detached external content. A plain model.execute commit does not.
            BmTransactions.writeInGlobalContext(model, "RemoveBusinessProcessRouteMap", mutation);
        }
        else
        {
            BmTransactions.write(model, "ReplaceBusinessProcessRouteMap", mutation);
        }
    }

    static Content readContent(IBmTransaction tx, BusinessProcess owner, ITopObjectFqnGenerator generator)
    {
        String schemeFqn = generator.generateExternalPropertyFqn(owner,
            MdClassPackage.Literals.BUSINESS_PROCESS__FLOWCHART);
        return new Content(schemeFqn, RouteMapWriter.existing(tx, schemeFqn));
    }

    record MapPreview(boolean exists, int items, int points, int connections, List<String> names,
        String discardedItems)
    {
        MapPreview(boolean exists, int items, int points, int connections, List<String> names)
        {
            this(exists, items, points, connections, names, points + " points and " + connections + " connections");
        }

        MapPreview
        {
            names = List.copyOf(names);
        }
    }

    /** Maps the detached read snapshot, including items with no point projection. */
    static MapPreview mapPreview(GraphicalScheme previous)
    {
        if (previous == null)
        {
            return new MapPreview(false, 0, 0, 0, List.of());
        }
        int points = 0;
        int connections = 0;
        int decorations = 0;
        Map<String, Integer> otherKinds = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (var item : previous.getItems())
        {
            if (item instanceof GraphicalSchemeFigure figure)
            {
                points++;
                if (figure.getItemType() == GraphicalSchemeItemType.DECORATION)
                {
                    decorations++;
                    otherKinds.merge("decoration", 1, Integer::sum);
                }
                if (figure.getName() != null)
                {
                    names.add(figure.getName());
                }
            }
            else if (item instanceof GraphicalSchemeConnection)
            {
                connections++;
            }
            else
            {
                String kind = item.getItemType() == null ? item.eClass().getName() : item.getItemType().getLiteral();
                otherKinds.merge(kind + " item", 1, Integer::sum);
            }
        }
        // Keep figure counts for get/remove's projection; consent lists decorations separately.
        List<String> discarded = new ArrayList<>();
        discarded.add((points - decorations) + " points");
        discarded.add(connections + " connections");
        otherKinds.forEach((kind, count) -> discarded.add(count + " " + kind + (count == 1 ? "" : "s")));
        return new MapPreview(true, previous.getItems().size(), points, connections, names,
            String.join(" and ", discarded));
    }

    /** The count fields describe written items for replace and discarded items for remove. */
    static ToolResult writeResult(String action, MapPreview previous, RouteMapRequest request)
    {
        boolean remove = action.equals("remove");
        return ToolResult.success().put("action", action).put("routeMapExists", !remove)
            .put("points", remove ? previous.points() : request.points().size())
            .put("connections", remove ? previous.connections() : request.connections().size());
    }

    /** One consent decision, outside BM transactions, immediately before any mutation. */
    String consentAndWrite(String action, String fqn, MapPreview previous, Supplier<String> write)
    {
        if (action.equals("remove") && !previous.exists())
        {
            return ToolResult.error("No route map exists on '" + fqn
                + "'. Use action='replace' to create one.").toJson();
        }
        if (action.equals("remove") || previous.items() > 0)
        {
            ConsentPreview preview = new ConsentPreview(
                (action.equals("remove") ? "Remove" : "Replace") + " route map of " + fqn,
                previous.discardedItems() + " are discarded; "
                    + "infobase tasks keep their point identity only for same-named points", previous.items(),
                previous.names());
            ConsentDecision decision = consent.request(NAME, preview);
            if (decision != ConsentDecision.ALLOW)
            {
                return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
            }
        }
        return write.get();
    }

    static BusinessProcess requireBusinessProcess(EObject object, boolean topLevel, String fqn)
    {
        if (object == null)
        {
            throw Refusals.argument("Metadata object '" + fqn
                + "' not found. Use get_metadata_objects to list BusinessProcess objects.");
        }
        if (!(object instanceof BusinessProcess) || !topLevel)
        {
            throw Refusals.argument("'" + fqn + "' is " + object.eClass().getName()
                + ", not a top-level BusinessProcess. Pass BusinessProcess.Name.");
        }
        return (BusinessProcess)object;
    }

    static void requireSupportedOwner(BusinessProcess owner)
    {
        if (ExtensionServices.isAdopted(owner))
        {
            throw Refusals.argument("Route maps of adopted BusinessProcess objects are deferred "
                + "to a follow-up. Use the EDT route-map editor; an extension-owned BusinessProcess is supported.");
        }
    }

    @Override
    protected String refreshAfterExportAwait(Map<String, String> params, String result, boolean drainEstablished)
    {
        JsonObject success = JsonParser.parseString(result).getAsJsonObject();
        if (!success.has("persisted"))
        {
            return result;
        }
        boolean remove = success.has("action") && success.get("action").getAsString().equals("remove");
        if (remove)
        {
            // Observe after the shared barrier: a drained queue alone cannot prove deletion.
            String file = success.has("file") ? success.get("file").getAsString() : null;
            Boolean present = com.ditrix.edt.mcp.server.utils.ProjectContext.fileOnDisk(
                params.get("projectName"), file);
            success.addProperty("persisted", Boolean.FALSE.equals(present));
        }
        List<String> slice = remove
            ? List.of(success.get("fqn").getAsString())
            : List.of(success.get("fqn").getAsString(), success.get("routeMapFqn").getAsString());
        WrittenObjectMarkers.Report report = WrittenObjectMarkers.collect(markerEnvironment(),
            Map.of(params.get("projectName"), slice), markerDeadlineMs());
        WrittenObjectMarkers.attach(success, report);
        markEmptySchemeSlice(success);
        return GsonProvider.toJson(success);
    }

    /** An empty external-resource slice is not proof of successful validation. */
    static void markEmptySchemeSlice(JsonObject success)
    {
        if (success.has("action") && success.get("action").getAsString().equals("remove"))
        {
            return;
        }
        String fqn = success.get("routeMapFqn").getAsString();
        boolean found = false;
        for (var marker : success.getAsJsonArray("markers"))
        {
            JsonObject row = marker.getAsJsonObject();
            found |= row.has("object") && fqn.equals(row.get("object").getAsString());
        }
        if (!found)
        {
            success.addProperty("markersIncomplete", true);
            if (!success.has("markersIncompleteReason"))
            {
                success.addProperty("markersIncompleteReason", "validationUnobservable");
            }
            String message = success.has("message") ? success.get("message").getAsString() : null;
            success.addProperty("message", WrittenObjectMarkers.join(message,
                "No markers were observed for the route-map FQN; "
                    + "validation is incomplete. Re-check with get_project_errors or the EDT route-map editor."));
            if (success.has("markerCount") && success.get("markerCount").getAsInt() == 0)
            {
                success.remove("markerCount");
            }
        }
    }
}
