/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeActivity;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeCondition;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeConnection;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeFigure;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItem;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeSubProcess;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeSwitch;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Read projection, including editor-authored kinds that v1 does not write. */
public final class RouteMapReader
{
    private RouteMapReader() { }

    public static JsonObject read(GraphicalScheme scheme, String language)
    {
        JsonObject result = new JsonObject();
        result.addProperty("routeMapExists", scheme != null);
        result.add("defaults", RouteMapGeometry.defaults());
        JsonArray points = new JsonArray();
        JsonArray connections = new JsonArray();
        result.add("points", points);
        result.add("connections", connections);
        if (scheme == null)
        {
            return result;
        }
        Map<String, GraphicalSchemeFigure> byName = new LinkedHashMap<>();
        for (GraphicalSchemeItem item : scheme.getItems())
        {
            if (item instanceof GraphicalSchemeFigure figure)
            {
                byName.put(figure.getName(), figure);
                JsonObject point = common(figure, language);
                point.addProperty("type", figure.getItemType().getLiteral());
                if (figure.getBounds() != null)
                {
                    JsonObject bounds = new JsonObject();
                    bounds.addProperty("x", figure.getBounds().getX());
                    bounds.addProperty("y", figure.getBounds().getY());
                    bounds.addProperty("width", figure.getBounds().getWidth());
                    bounds.addProperty("height", figure.getBounds().getHeight());
                    point.add("bounds", bounds);
                }
                if (figure instanceof GraphicalSchemeActivity activity)
                {
                    point.addProperty("taskDescription", activity.getTaskDescription());
                    point.addProperty("group", activity.isGroup());
                    JsonArray attributes = new JsonArray();
                    activity.getAddressingAttributes().forEach(attribute -> {
                        if (attribute.getRef() != null)
                        {
                            attributes.add(attribute.getRef().getName());
                        }
                    });
                    point.add("addressingAttributes", attributes);
                }
                if (figure instanceof GraphicalSchemeCondition condition)
                {
                    point.addProperty("truePort", condition.getTruePortIndex());
                    point.addProperty("falsePort", condition.getFalsePortIndex());
                }
                if (figure instanceof GraphicalSchemeSwitch selection)
                {
                    JsonArray cases = new JsonArray();
                    selection.getCases().forEach(value -> cases.add(value.getName()));
                    point.add("cases", cases);
                }
                if (figure instanceof GraphicalSchemeSubProcess subprocess && subprocess.getSubprocess() != null)
                {
                    String name = subprocessName(subprocess.getSubprocess());
                    if (name != null)
                    {
                        point.addProperty("subprocess", name);
                    }
                }
                points.add(point);
            }
        }
        Map<String, Integer> outgoingIndices = new LinkedHashMap<>();
        for (GraphicalSchemeItem item : scheme.getItems())
        {
            if (item instanceof GraphicalSchemeConnection connection)
            {
                JsonObject value = common(connection, language);
                value.addProperty("from", connection.getSourceItemName());
                value.addProperty("to", connection.getTargetItemName());
                value.addProperty("fromId", connection.getSourceItemId());
                value.addProperty("toId", connection.getTargetItemId());
                value.addProperty("fromPort", connection.getSourcePortIndex());
                value.addProperty("toPort", connection.getTargetPortIndex());
                value.addProperty("decorative", connection.isDecorativeLine());
                GraphicalSchemeFigure source = byName.get(connection.getSourceItemName());
                if (source instanceof GraphicalSchemeCondition condition)
                {
                    if (connection.getSourcePortIndex() == condition.getTruePortIndex())
                    {
                        value.addProperty("branch", "true");
                    }
                    else if (connection.getSourcePortIndex() == condition.getFalsePortIndex())
                    {
                        value.addProperty("branch", "false");
                    }
                }
                if (source instanceof GraphicalSchemeSwitch selection)
                {
                    int index = outgoingIndices.getOrDefault(source.getName(), 0);
                    outgoingIndices.put(source.getName(), index + 1);
                    value.addProperty("caseIndex", index);
                    if (index < selection.getCases().size())
                    {
                        value.addProperty("branch", selection.getCases().get(index).getName());
                    }
                }
                connections.add(value);
            }
        }
        return result;
    }

    private static String subprocessName(BusinessProcess target)
    {
        if (!target.eIsProxy() && target instanceof IBmObject object && object.bmIsTop())
        {
            return object.bmGetFqn();
        }
        if (target instanceof InternalEObject proxy)
        {
            var uri = proxy.eProxyURI();
            if (uri != null)
            {
                if (BmUriUtil.isBmUri(uri))
                {
                    return BmUriUtil.extractTopObjectFqn(uri);
                }
                if ("unresolved".equals(uri.scheme())) //$NON-NLS-1$
                {
                    String path = uri.path();
                    return path != null && path.startsWith("/") ? path.substring(1) : path; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    private static JsonObject common(GraphicalSchemeItem item, String language)
    {
        JsonObject value = new JsonObject();
        value.addProperty("name", item.getName());
        value.addProperty("id", item.getId());
        if (language != null && item.getTitle().containsKey(language))
        {
            value.addProperty("title", item.getTitle().get(language));
        }
        JsonArray handlers = new JsonArray();
        item.getEventHandlers().forEach(handler -> {
            if (handler.getName() != null && !handler.getName().isBlank())
            {
                JsonObject event = new JsonObject();
                event.addProperty("event", handler.getEvent() == null ? null : handler.getEvent().getName());
                event.addProperty("procedure", handler.getName());
                handlers.add(event);
            }
        });
        if (!handlers.isEmpty())
        {
            value.add("eventHandlers", handlers);
        }
        return value;
    }
}
