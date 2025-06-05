/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.coreui;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.sonatype.nexus.repository.config.UniqueRepositoryName;
import org.sonatype.nexus.validation.constraint.NamePatternConstants;
import org.sonatype.nexus.validation.group.Create;

/**
 * Repository exchange object.
 *
 * @since 3.0
 */
public record RepositoryXO(
    @Pattern(regexp = NamePatternConstants.REGEX, message = NamePatternConstants.MESSAGE)
    @NotEmpty
    @UniqueRepositoryName(groups = Create.class)
    String name,
    
    String type,
    
    String format,
    
    Long size,
    
    @NotBlank(groups = Create.class)
    String recipe,
    
    @NotNull
    Boolean online,
    
    String routingRuleId,
    
    @NotEmpty
    SequencedMap<String, Map<String, Object>> attributes,
    
    String url,
    
    RepositoryStatusXO status
) {
    /**
     * Compact constructor with conversion from regular Map to SequencedMap for backward compatibility.
     * This ensures existing code that passes a regular Map will still work with the new SequencedMap interface.
     */
    public RepositoryXO {
        if (!(attributes instanceof SequencedMap)) {
            attributes = new LinkedHashMap<>(attributes);
        }
    }
    
    /**
     * Provides a static method to create a new Builder instance.
     *
     * @return A new Builder for RepositoryXO.
     */
    public static Builder builder() {
        return new Builder();
    }

    // --- Builder Class ---
    public static class Builder {
        private String name;
        private String type;
        private String format;
        private Long size;
        private String recipe;
        private Boolean online;
        private String routingRuleId;
        private SequencedMap<String, Map<String, Object>> attributes;
        private String url;
        private RepositoryStatusXO status;

        // Private constructor to enforce usage of RepositoryXO.builder()
        private Builder() {
            // Initialize defaults if any are necessary
            this.online = false; // Example default
            this.attributes = new LinkedHashMap<>(); // Initialize to avoid NullPointerException
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder size(Long size) {
            this.size = size;
            return this;
        }

        public Builder recipe(String recipe) {
            this.recipe = recipe;
            return this;
        }

        public Builder online(Boolean online) {
            this.online = online;
            return this;
        }

        public Builder routingRuleId(String routingRuleId) {
            this.routingRuleId = routingRuleId;
            return this;
        }

        // Method to set the entire attributes map
        public Builder attributes(SequencedMap<String, Map<String, Object>> attributes) {
            this.attributes = attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
            return this;
        }

        // Optional: Method to add individual attributes for convenience
        public Builder addAttribute(String key, Map<String, Object> value) {
            if (this.attributes == null) {
                this.attributes = new LinkedHashMap<>();
            }
            this.attributes.put(key, value);
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder status(RepositoryStatusXO status) {
            this.status = status;
            return this;
        }

        /**
         * Builds the final RepositoryXO instance.
         * Note: Validation annotations on the record's fields are automatically
         * checked when the record constructor is called.
         * If you need manual validation, add it here.
         *
         * @return A new RepositoryXO instance.
         */
        public RepositoryXO build() {
            // Perform any necessary pre-build checks or default assignments here if not done in setters
            // For attributes, ensure it's not null before passing to the record constructor
            if (this.attributes == null) {
                this.attributes = new LinkedHashMap<>();
            }

            return new RepositoryXO(
                name,
                type,
                format,
                size,
                recipe,
                online,
                routingRuleId,
                attributes, // Already handled as SequencedMap by builder
                url,
                status
            );
        }
    }
    
}