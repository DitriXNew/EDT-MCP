/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataOrdering;
import com.ditrix.edt.mcp.server.utils.MetadataAutoSort;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ReferencedTopObjects;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/** Reorders metadata through BM list moves, then exports each changed file owner. */
public class SortMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "sort_metadata";
    private static final int SUMMARY_CAP = Pagination.DEFAULT_LIMIT;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Inspect current metadata order with mode=list, or reorder by Name, explicit order, or one sibling move. "
            + "Review order-sensitive changes: register DIMENSIONS define indexes and query speed; "
            + "enum values affect ORDER BY/comparisons; subsystem content affects command interface order. "
            + "Web-service parameter order defines handler signatures. "
            + "Other ordered collections can affect presentation or positional access. "
            + "Autosort maintains governed lists; ask the user about manual order. "
            + "Full parameters and examples: call get_tool_guide('sort_metadata').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "Configuration or extension project.", true)
            .stringProperty("fqn", "Level: Configuration, Configuration.Catalogs, Catalog.X, "
                + "or Catalog.X.Attributes. TYPE/KIND tokens accept Russian/English; Names are programmatic.", true)
            .enumProperty("mode", "Default alphabetical; list reads current order without writing. Explicit/move require a collection fqn.",
                "alphabetical", "explicit", "move", "list")
            .booleanProperty("recursive", "Default false. Alphabetical only: also order owned descendants.")
            .stringArrayProperty("items", "Explicit mode: sibling Names in desired order; unlisted members follow stably.")
            .stringProperty("objectName", "Move mode: one sibling's programmatic Name.")
            .enumProperty("direction", "Move mode: up/down, mutually exclusive with before/after.", "up", "down")
            .integerProperty("positions", "Positive move distance; default 1. Clamped at collection ends.")
            .stringProperty("before", "Move mode: place objectName before this sibling Name.")
            .stringProperty("after", "Move mode: place objectName after this sibling Name.")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation completed.", true)
            .stringProperty("fqn", "Requested level.")
            .stringProperty("mode", "'list' for a read-only order snapshot; omitted for sorting.")
            .booleanProperty("alreadySorted", "Sorting only: true if no collection changed.")
            .objectArrayProperty("collections", "All collections at the requested level: fqn, order, moved "
                + "({name,from,to}, 1-based), alreadySorted, autosort policy and warning when reordered. List returns fqn, order and autosort.")
            .objectArrayProperty("subCollections", "First 100 descendant summaries: fqn, size, movedCount, alreadySorted, autosort, warning.")
            .integerProperty("subCollectionCount", "Total descendant collections processed, including empty lists.")
            .booleanProperty("truncated", "Whether descendant summaries exceeded the cap.")
            .objectArrayProperty("sensitiveCollections", "Every reordered collection and its explicit order warning; never truncated.")
            .objectArrayProperty("autosortCollections", "First 100 autosort-maintained collections, including unchanged lists: fqn and policy.")
            .integerProperty("autosortCollectionCount", "Total autosort-maintained collections; compare with the summary length for truncation.")
            .booleanProperty("persisted", "Whether EDT accepted exports for all changed file owners; no-op is true.")
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION)
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn");
        if (missing != null)
        {
            return missing;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn");
        String mode = JsonUtils.extractStringArgument(params, "mode");
        boolean recursive = JsonUtils.extractBooleanArgument(params, "recursive", false);
        List<String> items = JsonUtils.extractArrayArgument(params, "items");
        String objectName = JsonUtils.extractStringArgument(params, "objectName");
        String direction = JsonUtils.extractStringArgument(params, "direction");
        int positions = JsonUtils.extractIntArgument(params, "positions", 0);
        String before = JsonUtils.extractStringArgument(params, "before");
        String after = JsonUtils.extractStringArgument(params, "after");
        Request request = new Request(mode == null ? "alphabetical" : mode, recursive, items,
            objectName, direction, params.containsKey("positions") ? positions : 1, before, after);
        try
        {
            request.validate(params.containsKey("positions"));
            ProjectContext ctx = resolveProjectAndConfig(projectName);
            if (ctx.hasError())
            {
                return ctx.error;
            }
            if (ctx.scope.isExternalObjects())
            {
                return ToolResult.error("Project '" + projectName + "' has standalone external objects, "
                    + "not a Configuration collection. Choose a configuration or extension project with list_projects.").toJson();
            }
            BmModelResolver.Resolution bm = BmModelResolver.resolve(ctx.project);
            if (!bm.isAvailable())
            {
                return ToolResult.error(bm.actionableError(NAME, "Nothing was changed.")).toJson();
            }
            MetadataAutoSort.Settings autosort = MetadataAutoSort.forProject(ctx.project);
            if ("list".equals(request.mode))
            {
                return readOrder(bm.getModel(), ctx.config, fqn, autosort);
            }
            Outcome outcome = reorder(bm.getModel(), ctx.config, fqn, request, autosort);
            boolean persisted = true;
            if (outcome.exports.isEmpty())
            {
                WriteScope.recordNothingQueued();
            }
            else
            {
                persisted = BmTransactions.forceExportToDisk(ctx.project, new ArrayList<>(outcome.exports));
                if (!persisted)
                {
                    return ToolResult.errorAfterMutation("Metadata order changed, but EDT did not accept its export. "
                        + "Use resync_to_disk for project '" + projectName + "', then read the order back.").toJson();
                }
            }
            String verification = refreshCommittedOrder(bm.getModel(), ctx.config, outcome);
            if (verification != null)
            {
                return ToolResult.errorAfterMutation(verification).toJson();
            }
            return MetadataAutoSort.summarize(ToolResult.success().put("fqn", fqn).put("collections", outcome.level)
                .put("subCollections", outcome.descendants).put("subCollectionCount", outcome.descendantCount)
                .put("truncated", outcome.descendantCount > SUMMARY_CAP)
                .put("sensitiveCollections", outcome.warnings).put("alreadySorted", outcome.exports.isEmpty())
                .put("autosortCollections", outcome.maintained).put("autosortCollectionCount", outcome.maintainedCount)
                .put("persisted", persisted), outcome.maintained).toJson();
        }
        catch (Exception e)
        {
            String refusal = MetadataAutoSort.refusalJson(e);
            if (refusal != null)
            {
                return refusal;
            }
            return ToolResult.error("Cannot " + ("list".equals(request.mode) ? "list" : "sort")
                + " metadata at '" + fqn + "': " + unwrapCauseMessage(e)).toJson();
        }
    }

    static List<Change> plan(Configuration config, String fqn, Request request, MetadataAutoSort.Settings autosort)
    {
        MetadataOrdering.Level level = MetadataOrdering.resolve(config, fqn);
        if (!"alphabetical".equals(request.mode) && !level.singleCollection())
        {
            throw new IllegalArgumentException("mode '" + request.mode
                + "' requires one collection fqn, e.g. Catalog.X.Attributes or Configuration.Catalogs.");
        }
        List<Change> changes = new ArrayList<>();
        Set<MdObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(level.owner(), level.collections(), request, false, changes, visited, autosort);
        return changes;
    }

    static Outcome reorder(IBmModel model, Configuration source, String fqn, Request request,
        MetadataAutoSort.Settings autosort)
    {
        Outcome unchanged = BmTransactions.read(model, "PlanMetadataOrder", (tx, pm) -> {
            List<Change> changes = plan(tx.toTransactionObject(source), fqn, request, autosort);
            if (changes.stream().anyMatch(change -> !change.moved.isEmpty()))
            {
                return null;
            }
            Outcome result = new Outcome();
            changes.forEach(result::add);
            return result;
        });
        if (unchanged != null)
        {
            return unchanged;
        }
        return BmTransactions.write(model, "SortMetadata", (tx, pm) -> {
            Configuration config = tx.toTransactionObject(source);
            MetadataOrdering.Level level = MetadataOrdering.resolve(config, fqn);
            List<Change> changes = plan(config, fqn, request, autosort);
            String levelRefusal = ownerRefusal(level.owner(), config, fqn);
            if (levelRefusal != null)
            {
                throw new IllegalArgumentException(levelRefusal);
            }
            // Validate every owner before the first move, including locked descendants.
            Set<MdObject> checked = Collections.newSetFromMap(new IdentityHashMap<>());
            checked.add(level.owner());
            for (Change change : changes)
            {
                if (!change.moved.isEmpty() && checked.add(change.owner))
                {
                    String refusal = ownerRefusal(change.owner, config, change.fqn);
                    if (refusal != null)
                    {
                        throw new IllegalArgumentException(refusal);
                    }
                }
            }
            Outcome result = new Outcome();
            for (Change change : changes)
            {
                change.apply();
                result.add(change);
            }
            return result;
        });
    }

    static String readOrder(IBmModel model, Configuration config, String fqn)
    {
        return readOrder(model, config, fqn, MetadataAutoSort.disabled());
    }

    static String readOrder(IBmModel model, Configuration config, String fqn, MetadataAutoSort.Settings autosort)
    {
        List<Map<String, Object>> rows = BmTransactions.read(model, "ListMetadataOrder", (tx, pm) -> {
            MetadataOrdering.Level level = MetadataOrdering.resolve(tx.toTransactionObject(config), fqn);
            List<Map<String, Object>> snapshot = new ArrayList<>();
            for (EReference ref : level.collections())
            {
                String address = MetadataOrdering.collectionAddress(level.owner(), ref);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fqn", address);
                row.put("order", MetadataOrdering.members(level.owner(), ref).stream().map(MdObject::getName).toList());
                MetadataAutoSort.putNotice(row, autosort.forCollection(ref), address, MetadataAutoSort.MAINTAINED);
                snapshot.add(row);
            }
            return snapshot;
        });
        WriteScope.recordNothingQueued();
        return MetadataAutoSort.summarize(ToolResult.success().put("mode", "list").put("fqn", fqn)
            .put("collections", rows), rows).toJson();
    }

    static String refreshCommittedOrder(IBmModel model, Configuration config, Outcome outcome)
    {
        Map<String, List<String>> committed = BmTransactions.read(model, "VerifyMetadataOrder", (tx, pm) -> {
            Configuration current = tx.toTransactionObject(config);
            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            for (String address : outcome.observedOrders.keySet())
            {
                MetadataOrdering.Level level = MetadataOrdering.resolve(current, address);
                snapshot.put(address, MetadataOrdering.members(level.owner(), level.collections().get(0))
                    .stream().map(MdObject::getName).toList());
            }
            return snapshot;
        });
        for (var entry : committed.entrySet())
        {
            if (!entry.getValue().equals(outcome.observedOrders.get(entry.getKey())))
            {
                return "EDT committed a different order at '" + entry.getKey() + "': expected "
                    + outcome.observedOrders.get(entry.getKey()) + "; model reports " + entry.getValue()
                    + ". Read mode=list before retrying the move.";
            }
        }
        for (Map<String, Object> row : outcome.level)
        {
            row.put("order", committed.get(row.get("fqn")));
        }
        return null;
    }

    private static String ownerRefusal(MdObject owner, Configuration config, String fqn)
    {
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        return owner == config
            ? VendorSupportGuard.refusalForFqn(scope, "Configuration", VendorSupportGuard.Intent.MODIFY)
            : VendorSupportGuard.refusalFor(owner, scope, fqn, fqn, "reordered");
    }

    record Request(String mode, boolean recursive, List<String> items, String objectName,
        String direction, int positions, String before, String after)
    {
        void validate(boolean hasPositions)
        {
            if (!Set.of("alphabetical", "explicit", "move", "list").contains(mode))
            {
                throw new IllegalArgumentException("Unknown mode '" + mode + "'; use alphabetical, explicit, move or list.");
            }
            if (recursive && !"alphabetical".equals(mode))
            {
                throw new IllegalArgumentException("recursive=true requires mode alphabetical; list/explicit/move are single-level only.");
            }
            if ((items != null) != "explicit".equals(mode))
            {
                throw new IllegalArgumentException("items is required only for mode explicit; omit it for other modes.");
            }
            if ("move".equals(mode))
            {
                int anchors = (direction == null ? 0 : 1) + (before == null ? 0 : 1) + (after == null ? 0 : 1);
                if (objectName == null || objectName.isBlank() || anchors != 1
                    || (direction != null && !Set.of("up", "down").contains(direction))
                    || positions < 1 || (hasPositions && direction == null))
                {
                    throw new IllegalArgumentException("mode move requires objectName and exactly one of direction "
                        + "(up/down, positive integer positions), before or after. Omit incompatible move parameters.");
                }
            }
            else if (objectName != null || direction != null || hasPositions || before != null || after != null)
            {
                throw new IllegalArgumentException("objectName/direction/positions/before/after require mode move; omit them otherwise.");
            }
        }
    }

    static void collect(MdObject owner, List<EReference> refs, Request request, boolean descendant,
        List<Change> changes, Set<MdObject> visited)
    {
        collect(owner, refs, request, descendant, changes, visited, MetadataAutoSort.disabled());
    }

    static void collect(MdObject owner, List<EReference> refs, Request request, boolean descendant,
        List<Change> changes, Set<MdObject> visited, MetadataAutoSort.Settings autosort)
    {
        if (!visited.add(owner))
        {
            return;
        }
        for (EReference ref : refs)
        {
            Change change = new Change(owner, ref, request, descendant, autosort.forCollection(ref));
            changes.add(change);
            if (request.recursive && MetadataOrdering.ownsChildren(owner, ref))
            {
                for (MdObject child : change.ordered)
                {
                    collect(child, MetadataOrdering.collections(child), request, true, changes, visited, autosort);
                }
            }
        }
    }

    static final class Change
    {
        final MdObject owner;
        final EReference ref;
        final String fqn;
        final boolean descendant;
        final List<MdObject> original;
        final List<MdObject> ordered;
        final List<String> names;
        final List<Map<String, Object>> moved;
        final MetadataAutoSort.Policy autosort;

        Change(MdObject owner, EReference ref, Request request, boolean descendant)
        {
            this(owner, ref, request, descendant, MetadataAutoSort.disabled().forCollection(ref));
        }

        Change(MdObject owner, EReference ref, Request request, boolean descendant, MetadataAutoSort.Policy autosort)
        {
            this.owner = owner;
            this.ref = ref;
            this.descendant = descendant;
            this.fqn = MetadataOrdering.collectionAddress(owner, ref);
            this.autosort = autosort;
            if (!"alphabetical".equals(request.mode))
            {
                autosort.requireManualOrder(fqn);
            }
            original = new ArrayList<>(MetadataOrdering.members(owner, ref));
            List<String> originalNames = original.stream().map(MdObject::getName).toList();
            if (originalNames.stream().anyMatch(name -> name == null || name.isBlank()))
            {
                throw new IllegalArgumentException("Collection '" + fqn + "' contains an unresolved or unnamed member. "
                    + "Resolve the project's get_project_errors before sorting.");
            }
            if (!"alphabetical".equals(request.mode)
                && originalNames.stream().map(n -> n.toLowerCase(Locale.ROOT)).distinct().count() != originalNames.size())
            {
                throw new IllegalArgumentException("Ambiguous sibling Names in '" + fqn
                    + "'; use alphabetical mode for this heterogeneous reference collection.");
            }
            List<String> requestedNames = MetadataOrdering.order(originalNames, request.mode, request.items, request.objectName,
                request.direction, request.positions, request.before, request.after);
            ordered = new ArrayList<>();
            Map<String, Deque<MdObject>> byName = new HashMap<>();
            for (int i = 0; i < original.size(); i++)
            {
                MdObject object = original.get(i);
                byName.computeIfAbsent(object.getName(), key -> new ArrayDeque<>()).add(object);
            }
            for (int i = 0; i < requestedNames.size(); i++)
            {
                String name = requestedNames.get(i);
                MdObject object = byName.get(name).removeFirst();
                ordered.add(object);
            }
            if (autosort.active())
            {
                ordered.clear();
                ordered.addAll(original);
                ordered.sort(autosort.comparator());
            }
            names = ordered.stream().map(MdObject::getName).toList();
            moved = MetadataOrdering.moved(original, ordered, MdObject::getName);
        }

        void apply()
        {
            EList<MdObject> list = MetadataOrdering.members(owner, ref);
            List<MdObject> current = new ArrayList<>(list);
            for (int i = 0; i < ordered.size(); i++)
            {
                int source = current.indexOf(ordered.get(i));
                if (source != i)
                {
                    // Use positions from the snapshot, avoiding BM's separate reference lookup.
                    list.move(i, source);
                    current.add(i, current.remove(source));
                }
            }
            if (!new ArrayList<>(list).equals(ordered))
            {
                throw new IllegalStateException("EDT did not apply order at '" + fqn + "': expected "
                    + names + "; model reports " + snapshot().names() + ". Read mode=list before retrying.");
            }
        }

        AppliedOrder snapshot()
        {
            List<MdObject> actual = new ArrayList<>(MetadataOrdering.members(owner, ref));
            return new AppliedOrder(actual.stream().map(MdObject::getName).toList(),
                MetadataOrdering.moved(original, actual, MdObject::getName));
        }
    }

    record AppliedOrder(List<String> names, List<Map<String, Object>> moved)
    {
    }

    static final class Outcome
    {
        final List<Map<String, Object>> level = new ArrayList<>();
        final List<Map<String, Object>> descendants = new ArrayList<>();
        final List<Map<String, Object>> warnings = new ArrayList<>();
        final List<Map<String, Object>> maintained = new ArrayList<>();
        int maintainedCount;
        final Set<String> exports = new LinkedHashSet<>();
        final Map<String, List<String>> observedOrders = new LinkedHashMap<>();
        int descendantCount;

        void add(Change change)
        {
            AppliedOrder actual = change.snapshot();
            observedOrders.put(change.fqn, actual.names());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fqn", change.fqn);
            MetadataAutoSort.putNotice(row, change.autosort, change.fqn, MetadataAutoSort.MAINTAINED);
            if (change.autosort.active())
            {
                maintainedCount++;
                if (maintained.size() < SUMMARY_CAP)
                {
                    maintained.add(Map.of("fqn", change.fqn, "autosort", row.get("autosort")));
                }
            }
            row.put("alreadySorted", actual.moved().isEmpty());
            if (!actual.moved().isEmpty())
            {
                String warning = MetadataOrdering.warning(change.owner, change.ref);
                row.put("warning", warning);
                warnings.add(Map.of("fqn", change.fqn, "warning", warning));
                exports.add(ReferencedTopObjects.fileOwnerFqn(change.owner));
            }
            if (change.descendant)
            {
                descendantCount++;
                row.put("size", actual.names().size());
                row.put("movedCount", actual.moved().size());
                if (descendants.size() < SUMMARY_CAP)
                {
                    descendants.add(row);
                }
            }
            else
            {
                row.put("order", actual.names());
                row.put("moved", actual.moved());
                level.add(row);
            }
        }
    }
}
