/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.eclipse.core.runtime.Path;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;
import com.ditrix.edt.mcp.server.utils.yaml.AbstractProjectYamlStore;

/** Tag storage using the shared project cache, YAML loader and file lock. */
public class TagYamlStore extends AbstractProjectYamlStore<TagStorage> {
    public TagYamlStore() {
        super(TagStorage.class, new Path(TagConstants.SETTINGS_FOLDER).append(TagConstants.TAGS_FILE));
    }

    @Override
    protected TagStorage emptyStorage() {
        return new TagStorage();
    }

    @Override
    protected TagStorage copy(TagStorage storage) {
        TagStorage result = new TagStorage();
        List<Tag> detachedTags = new ArrayList<>();
        for (Tag tag : storage.getTags()) {
            Tag detached = new Tag();
            detached.setName(tag.getName());
            detached.setColor(tag.getColor());
            detached.setDescription(tag.getDescription());
            detachedTags.add(detached);
        }
        result.setTags(detachedTags);
        Map<String, List<String>> assignments = new LinkedHashMap<>();
        storage.getAssignments().forEach((fqn, names) -> assignments.put(fqn, new ArrayList<>(names)));
        result.setAssignments(assignments);
        return result;
    }

    @Override
    public String dump(TagStorage storage) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Representer representer = new Representer(options);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        representer.addClassTag(TagStorage.class, org.yaml.snakeyaml.nodes.Tag.MAP);
        representer.addClassTag(Tag.class, org.yaml.snakeyaml.nodes.Tag.MAP);
        return new Yaml(representer, options).dump(storage);
    }
}
