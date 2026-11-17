/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.runtime.Path;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import com.ditrix.edt.mcp.server.groups.GroupConstants;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.model.GroupStorage;
import com.ditrix.edt.mcp.server.utils.yaml.AbstractProjectYamlStore;

/** Group YAML formatting backed by the shared project store. */
public class YamlGroupRepository extends AbstractProjectYamlStore<GroupStorage> implements IGroupRepository {
    public YamlGroupRepository() {
        super(GroupStorage.class, new Path(GroupConstants.SETTINGS_FOLDER).append(GroupConstants.GROUPS_FILE));
    }

    private static final Comparator<Group> GIT_FRIENDLY_ORDER = Comparator
        .comparing((Group g) -> g.getPath() != null ? g.getPath() : "")
        .thenComparingInt(Group::getOrder)
        .thenComparing(Group::getName, String.CASE_INSENSITIVE_ORDER);
    
    @Override
    protected GroupStorage emptyStorage() {
        return new GroupStorage();
    }

    @Override
    protected GroupStorage copy(GroupStorage storage) {
        GroupStorage result = new GroupStorage();
        List<Group> groups = new ArrayList<>();
        for (Group group : storage.getGroups()) {
            Group detached = new Group(group.getName(), group.getPath());
            detached.setDescription(group.getDescription());
            detached.setOrder(group.getOrder());
            detached.setChildren(group.getChildren());
            groups.add(detached);
        }
        result.setGroups(groups);
        return result;
    }

    @Override
    protected boolean omitEmpty(GroupStorage storage) {
        return storage.getGroups().isEmpty();
    }

    @Override
    public String dump(GroupStorage storage) {
        GroupStorage sortedStorage = copy(storage);
        sortedStorage.getGroups().sort(GIT_FRIENDLY_ORDER);
        for (Group group : sortedStorage.getGroups()) {
            List<String> children = new ArrayList<>(group.getChildren());
            Collections.sort(children, String.CASE_INSENSITIVE_ORDER);
            group.setChildren(children);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setWidth(120);
        options.setSplitLines(false);
        Representer representer = new SortedKeysRepresenter(options);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        representer.addClassTag(GroupStorage.class, Tag.MAP);
        representer.addClassTag(Group.class, Tag.MAP);
        return new Yaml(representer, options).dump(sortedStorage);
    }

    private static class SortedKeysRepresenter extends Representer {
        
        public SortedKeysRepresenter(DumperOptions options) {
            super(options);
        }
        
        @Override
        protected org.yaml.snakeyaml.nodes.MappingNode representJavaBean(
                java.util.Set<org.yaml.snakeyaml.introspector.Property> properties, Object javaBean) {
            // Sort properties by name for consistent output
            List<org.yaml.snakeyaml.introspector.Property> sorted = new ArrayList<>(properties);
            sorted.sort(Comparator.comparing(org.yaml.snakeyaml.introspector.Property::getName));
            return super.representJavaBean(new java.util.LinkedHashSet<>(sorted), javaBean);
        }
    }
}
