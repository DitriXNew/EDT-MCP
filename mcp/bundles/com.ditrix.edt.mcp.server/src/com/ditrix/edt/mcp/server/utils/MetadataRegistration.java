// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/** Structural registration checks on exported bytes, independent of BM and export queues. */
public final class MetadataRegistration
{
    public enum State { PRESENT, ABSENT, UNVERIFIABLE }
    private MetadataRegistration() { }

    public static State verify(String projectName, String file, String container, String fqn)
    {
        if (projectName == null || file == null || container == null || fqn == null)
            return State.UNVERIFIABLE;
        Element root = readRoot(projectName, file, container);
        return root == null ? State.UNVERIFIABLE
            : containsRegistration(root, container, fqn) ? State.PRESENT : State.ABSENT;
    }

    public static boolean verifyRename(String projectName, String oldFqn, String newFqn)
    {
        Element root = readRoot(projectName, "src/Configuration/Configuration.mdo", "Configuration");
        if (root == null) return false;
        String feature = MetadataTypeUtils.getConfigReferenceName(newFqn.split("\\.")[0]);
        boolean found = false;
        for (Element child : directChildren(root, feature))
        {
            String registered = simpleText(child);
            if (registered.isEmpty()) registered = newFqn.split("\\.")[0] + "." + directChildText(child, "name");
            if (registered.equalsIgnoreCase(oldFqn) && !registered.equals(newFqn)) return false;
            found |= registered.equals(newFqn);
        }
        return found;
    }

    private static Element readRoot(String projectName, String file, String container)
    {
        try
        {
            ProjectContext context = ProjectContext.of(projectName);
            var location = context.exists() ? context.project().getFile(file).getLocation() : null;
            if (location == null) return null;
            try (var input = Files.newInputStream(location.toFile().toPath()))
            {
                Element root = SecureXml.documentBuilderFactory().newDocumentBuilder()
                    .parse(input).getDocumentElement();
                return container.split("\\.")[0].equals(elementName(root)) ? root : null;
            }
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Pure structural check shared by the exported-file verifiers. */
    public static boolean containsRegistration(Element root, String registeringContainer, String targetFqn)
    {
        if (root == null || registeringContainer == null || targetFqn == null)
        {
            return false;
        }
        if ("Configuration".equals(registeringContainer)) //$NON-NLS-1$
        {
            String[] target = targetFqn.split("\\."); //$NON-NLS-1$
            if (target.length != 2)
            {
                return false;
            }
            String feature = MetadataTypeUtils.getConfigReferenceName(target[0]);
            for (Element child : directChildren(root, feature))
            {
                if (targetFqn.equalsIgnoreCase(simpleText(child))
                    || target[1].equalsIgnoreCase(directChildText(child, "name")))
                {
                    return true;
                }
            }
            return false;
        }

        String prefix = registeringContainer + "."; //$NON-NLS-1$
        if (targetFqn.length() <= prefix.length()
            || !targetFqn.regionMatches(true, 0, prefix, 0, prefix.length()))
        {
            return false;
        }
        String[] remainder = targetFqn.substring(prefix.length()).split("\\."); //$NON-NLS-1$
        if (remainder.length == 0 || (remainder.length & 1) != 0)
        {
            return false;
        }
        Element current = root;
        var classifier = MdClassPackage.eINSTANCE.getEClassifier(elementName(root));
        EClass ownerType = classifier instanceof EClass ? (EClass)classifier : null;
        String addressedPrefix = registeringContainer;
        for (int i = 0; i < remainder.length; i += 2)
        {
            // Subsystem registration is a reference, outside the generic containment-kind map.
            boolean subsystem = SubsystemUtils.isSubsystemTypeToken(remainder[i]);
            EReference reference = MetadataNodeResolver.childFeature(ownerType, remainder[i]);
            String feature = subsystem ? "subsystems" //$NON-NLS-1$
                : reference == null ? null : reference.getName();
            if (feature == null)
            {
                return false;
            }
            String name = remainder[i + 1];
            addressedPrefix += "." + remainder[i] + "." + name; //$NON-NLS-1$ //$NON-NLS-2$
            Element match = null;
            for (Element candidate : directChildren(current, feature))
            {
                // EDT writes a subsystem reference as the bare child Name; tolerate a full chain too.
                if ((subsystem && name.equalsIgnoreCase(simpleText(candidate)))
                    || addressedPrefix.equalsIgnoreCase(simpleText(candidate))
                    || name.equalsIgnoreCase(directChildText(candidate, "name"))) //$NON-NLS-1$
                {
                    match = candidate;
                    break;
                }
            }
            if (match == null)
            {
                return false;
            }
            current = match;
            ownerType = reference == null ? null : reference.getEReferenceType();
        }
        return true;
    }

    /** Direct children in the document's vocabulary; foreign namespaces do not register metadata. */
    public static List<Element> directChildren(Element parent, String name)
    {
        List<Element> result = new ArrayList<>();
        if (parent == null || name == null)
        {
            return result;
        }
        Document owner = parent.getOwnerDocument();
        Element root = owner == null ? null : owner.getDocumentElement();
        String documentNamespace = root == null ? null : root.getNamespaceURI();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child instanceof Element element && name.equals(elementName(element))
                && belongsToDocument(element, documentNamespace))
            {
                result.add(element);
            }
        }
        return result;
    }

    /** Whether an element is part of the document's own vocabulary rather than a foreign one. */
    private static boolean belongsToDocument(Element element, String documentNamespace)
    {
        String namespace = element.getNamespaceURI();
        return namespace == null || namespace.equals(documentNamespace);
    }

    public static String directChildText(Element parent, String name)
    {
        List<Element> children = directChildren(parent, name);
        return children.isEmpty() ? "" : children.get(0).getTextContent().trim(); //$NON-NLS-1$
    }

    public static String simpleText(Element element)
    {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            if (children.item(i) instanceof Element)
            {
                return ""; //$NON-NLS-1$
            }
        }
        return element.getTextContent().trim();
    }

    public static String elementName(Element element)
    {
        String local = element.getLocalName();
        return local == null ? element.getNodeName() : local;
    }

}
