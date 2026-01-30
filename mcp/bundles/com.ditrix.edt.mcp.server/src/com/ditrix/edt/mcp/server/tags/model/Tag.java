/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.model;

import java.util.Objects;

/**
 * Represents a single tag that can be applied to metadata objects.
 */
public class Tag {
    
    private String name;
    private String color;
    private String description;
    
    /**
     * Creates a new tag with the given name.
     * 
     * @param name the tag name
     */
    public Tag(String name) {
        this(name, "#808080", "");
    }
    
    /**
     * Creates a new tag with the given name and color.
     * 
     * @param name the tag name
     * @param color the tag color in hex format (e.g., "#FF0000")
     */
    public Tag(String name, String color) {
        this(name, color, "");
    }
    
    /**
     * Creates a new tag with all properties.
     * 
     * @param name the tag name
     * @param color the tag color in hex format
     * @param description optional description
     */
    public Tag(String name, String color, String description) {
        this.name = Objects.requireNonNull(name, "Tag name cannot be null");
        this.color = color != null ? color : "#808080";
        this.description = description != null ? description : "";
    }
    
    /**
     * Default constructor for YAML deserialization.
     */
    public Tag() {
        this.name = "";
        this.color = "#808080";
        this.description = "";
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getColor() {
        return color;
    }
    
    public void setColor(String color) {
        this.color = color;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Tag other = (Tag) obj;
        return Objects.equals(name, other.name);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
    
    @Override
    public String toString() {
        return name;
    }
}
