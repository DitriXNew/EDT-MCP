/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.bp.scheme.GraphicalSchemeObjectFactory;
import com._1c.g5.v8.dt.bp.scheme.IdGenerator;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeActivity;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeCondition;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeConnectionTextLocation;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeFigure;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItem;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeLineType;
import com._1c.g5.v8.dt.bp.scheme.model.ModelFactory;
import com._1c.g5.v8.dt.bp.scheme.model.ModelPackage;
import com._1c.g5.v8.dt.bp.scheme.services.AddressingAttributeServiceProvider;
import com._1c.g5.v8.dt.bp.scheme.services.BpSchemeUtil;
import com._1c.g5.v8.dt.bp.scheme.services.DiagramNameProvider;
import com._1c.g5.v8.dt.metadata.mdclass.AddressingAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.platform.version.Version;

import com.ditrix.edt.mcp.server.utils.Refusals;

/** Builds the editor's model. Call only in a BM write transaction. */
public final class RouteMapWriter
{
    private static final ModelFactory FACTORY = ModelFactory.eINSTANCE;

    private RouteMapWriter() { }

    /** Resolves external content directly, never through the owner's inferred proxy. */
    public static GraphicalScheme existing(IBmTransaction tx, String schemeFqn)
    {
        Object content = tx.getTopObjectByFqn(schemeFqn);
        if (content != null && !(content instanceof GraphicalScheme))
        {
            throw Refusals.argument("Route-map content '" + schemeFqn
                + "' is not a GraphicalScheme. Refresh the project and retry.");
        }
        return (GraphicalScheme)content;
    }

    public static GraphicalScheme attach(IBmTransaction tx, String schemeFqn, Version version)
    {
        GraphicalScheme scheme = new GraphicalSchemeObjectFactory().create(ModelPackage.Literals.GRAPHICAL_SCHEME,
            version);
        if (scheme == null)
        {
            throw Refusals.state("EDT could not create a route map. Refresh the project and retry.");
        }
        tx.attachTopObject((IBmObject)scheme, schemeFqn);
        return scheme;
    }

    public static void replace(GraphicalScheme scheme, RouteMapRequest request, String language, BusinessProcess owner)
    {
        replace(scheme, request, language, owner,
            bp -> AddressingAttributeServiceProvider.getInstance().getService()
                .collectAddressingAttributeForGraphicalScheme(bp));
    }

    /** The seam supplies service results without changing the platform singleton. */
    public static void replace(GraphicalScheme scheme, RouteMapRequest request, String language, BusinessProcess owner,
        Function<BusinessProcess, Collection<AddressingAttribute>> addressing)
    {
        Collection<AddressingAttribute> attributes = request.points().stream()
            .anyMatch(point -> point.type() == GraphicalSchemeItemType.ACTIVITY)
                ? new ArrayList<>(addressing.apply(owner)) : java.util.List.of();
        Map<String, UUID> identities = new LinkedHashMap<>();
        int maximum = 0;
        for (GraphicalSchemeItem item : scheme.getItems())
        {
            maximum = Math.max(maximum, item.getId());
            if (item instanceof GraphicalSchemeFigure && item.getPointUUID() != null)
            {
                identities.put(item.getName(), item.getPointUUID());
            }
        }
        IdGenerator ids = new IdGenerator();
        ids.setCurrentId(Math.addExact(maximum, 1));
        Map<String, RouteMapGeometry.Rectangle> geometry = RouteMapGeometry.place(request);
        Map<String, GraphicalSchemeFigure> figures = new LinkedHashMap<>();
        Collection<String> takenNames = new LinkedHashSet<>();
        request.points().forEach(point -> takenNames.add(point.name()));
        request.connections().forEach(connection -> {
            if (connection.name() != null)
            {
                takenNames.add(connection.name());
            }
        });
        // External content settings belong to the document, not to its replaceable items.
        scheme.getItems().clear();
        for (var point : request.points())
        {
            GraphicalSchemeFigure figure = switch (point.type())
            {
                case ACTIVITY -> FACTORY.createGraphicalSchemeActivity();
                case CONDITION -> FACTORY.createGraphicalSchemeCondition();
                default -> FACTORY.createGraphicalSchemeFigure();
            };
            initialize(figure, ids.generateId(), point.name(), point.title(), language);
            figure.setItemType(point.type());
            UUID previous = identities.get(point.name());
            figure.setPointUUID(previous != null ? previous : UUID.randomUUID());
            var rectangle = geometry.get(point.name());
            var bounds = FACTORY.createBounds();
            bounds.setX(rectangle.x());
            bounds.setY(rectangle.y());
            bounds.setWidth(rectangle.width());
            bounds.setHeight(rectangle.height());
            figure.setBounds(bounds);
            if (figure instanceof GraphicalSchemeCondition condition)
            {
                condition.setTruePortIndex(3);
                condition.setFalsePortIndex(1);
            }
            if (figure instanceof GraphicalSchemeActivity activity)
            {
                activity.setTaskDescription(point.taskDescription() == null ? point.name() : point.taskDescription());
                activity.setGroup(point.group());
                for (AddressingAttribute attribute : attributes)
                {
                    activity.getAddressingAttributes().add(BpSchemeUtil.createItemAddressingAttribute(attribute));
                }
            }
            scheme.getItems().add(figure);
            figures.put(point.name(), figure);
        }
        for (var connection : request.connections())
        {
            var relation = FACTORY.createGraphicalSchemeConnection();
            String name = connection.name() == null
                ? BpSchemeUtil.getAvailableName(DiagramNameProvider.getDefaultRelationName(), takenNames)
                : connection.name();
            takenNames.add(name);
            initialize(relation, ids.generateId(), name, connection.title(), language);
            relation.setItemType(GraphicalSchemeItemType.CONNECTION_LINE);
            relation.setSourceItemName(connection.from());
            relation.setSourceItemId(figures.get(connection.from()).getId());
            relation.setTargetItemName(connection.to());
            relation.setTargetItemId(figures.get(connection.to()).getId());
            relation.setSourcePortIndex(connection.fromPort());
            relation.setTargetPortIndex(connection.toPort());
            relation.setTextLocation(GraphicalSchemeConnectionTextLocation.FIRST_SEGMENT);
            scheme.getItems().add(relation);
        }
    }

    private static void initialize(GraphicalSchemeItem item, int id, String name, String title, String language)
    {
        item.setId(id);
        item.setName(name);
        if (title != null && language != null)
        {
            item.getTitle().put(language, title);
        }
        var line = FACTORY.createGraphicalSchemeLine();
        line.setStyle(GraphicalSchemeLineType.SOLID);
        line.setWidth(1);
        line.setGap(false);
        item.setLine(line);
    }
}
