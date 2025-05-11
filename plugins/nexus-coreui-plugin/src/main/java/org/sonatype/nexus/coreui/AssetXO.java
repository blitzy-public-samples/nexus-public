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

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

import javax.validation.constraints.NotEmpty;

/**
 * Asset exchange object.
 *
 * @since 3.0
 * @since 3.x Refactored as a Java record for Java 21 compatibility
 */
public record AssetXO(
    @NotEmpty String id,
    @NotEmpty String name,
    @NotEmpty String format,
    @NotEmpty String contentType,
    @NotEmpty long size,
    @NotEmpty String repositoryName,
    @NotEmpty String containingRepositoryName,
    Date blobCreated,
    Date blobUpdated,
    Date lastDownloaded,
    @NotEmpty String blobRef,
    String componentId,
    String createdBy,
    String createdByIp,
    @NotEmpty SequencedMap<String, Object> attributes
) {
    /**
     * Constructor with LinkedHashMap for attributes, which implements SequencedMap in Java 21.
     * This compact constructor validates and normalizes the input parameters.
     */
    public AssetXO {
        // Validate required fields
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(format, "format cannot be null");
        Objects.requireNonNull(contentType, "contentType cannot be null");
        Objects.requireNonNull(repositoryName, "repositoryName cannot be null");
        Objects.requireNonNull(containingRepositoryName, "containingRepositoryName cannot be null");
        Objects.requireNonNull(blobRef, "blobRef cannot be null");
        Objects.requireNonNull(attributes, "attributes cannot be null");
        
        // Convert Map to LinkedHashMap if it's not already a SequencedMap
        if (!(attributes instanceof SequencedMap)) {
            attributes = new LinkedHashMap<>(attributes);
        }
    }

    /**
     * Factory method to create an AssetXO from a traditional Map for backward compatibility.
     * This method is useful for code that hasn't been updated to use records directly.
     *
     * @param id The asset ID
     * @param name The asset name
     * @param format The asset format
     * @param contentType The content type
     * @param size The size in bytes
     * @param repositoryName The repository name
     * @param containingRepositoryName The containing repository name
     * @param blobCreated The blob creation date
     * @param blobUpdated The blob update date
     * @param lastDownloaded The last downloaded date
     * @param blobRef The blob reference
     * @param componentId The component ID
     * @param createdBy The creator
     * @param createdByIp The creator's IP
     * @param attributes The attributes map
     * @return A new AssetXO instance
     */
    public static AssetXO of(
        String id,
        String name,
        String format,
        String contentType,
        long size,
        String repositoryName,
        String containingRepositoryName,
        Date blobCreated,
        Date blobUpdated,
        Date lastDownloaded,
        String blobRef,
        String componentId,
        String createdBy,
        String createdByIp,
        Map<String, Object> attributes
    ) {
        return new AssetXO(
            id,
            name,
            format,
            contentType,
            size,
            repositoryName,
            containingRepositoryName,
            blobCreated,
            blobUpdated,
            lastDownloaded,
            blobRef,
            componentId,
            createdBy,
            createdByIp,
            attributes instanceof SequencedMap ? (SequencedMap<String, Object>) attributes : new LinkedHashMap<>(attributes)
        );
    }
    
    /**
     * Factory method to create a builder for AssetXO.
     * This provides a more flexible way to create instances when many fields are optional.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder class for AssetXO to support pattern matching and flexible object creation.
     */
    public static class Builder {
        private String id;
        private String name;
        private String format;
        private String contentType;
        private long size;
        private String repositoryName;
        private String containingRepositoryName;
        private Date blobCreated;
        private Date blobUpdated;
        private Date lastDownloaded;
        private String blobRef;
        private String componentId;
        private String createdBy;
        private String createdByIp;
        private Map<String, Object> attributes = new LinkedHashMap<>();
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder format(String format) {
            this.format = format;
            return this;
        }
        
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }
        
        public Builder size(long size) {
            this.size = size;
            return this;
        }
        
        public Builder repositoryName(String repositoryName) {
            this.repositoryName = repositoryName;
            return this;
        }
        
        public Builder containingRepositoryName(String containingRepositoryName) {
            this.containingRepositoryName = containingRepositoryName;
            return this;
        }
        
        public Builder blobCreated(Date blobCreated) {
            this.blobCreated = blobCreated;
            return this;
        }
        
        public Builder blobUpdated(Date blobUpdated) {
            this.blobUpdated = blobUpdated;
            return this;
        }
        
        public Builder lastDownloaded(Date lastDownloaded) {
            this.lastDownloaded = lastDownloaded;
            return this;
        }
        
        public Builder blobRef(String blobRef) {
            this.blobRef = blobRef;
            return this;
        }
        
        public Builder componentId(String componentId) {
            this.componentId = componentId;
            return this;
        }
        
        public Builder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }
        
        public Builder createdByIp(String createdByIp) {
            this.createdByIp = createdByIp;
            return this;
        }
        
        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = new LinkedHashMap<>(attributes);
            return this;
        }
        
        public Builder addAttribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }
        
        public AssetXO build() {
            return new AssetXO(
                id,
                name,
                format,
                contentType,
                size,
                repositoryName,
                containingRepositoryName,
                blobCreated,
                blobUpdated,
                lastDownloaded,
                blobRef,
                componentId,
                createdBy,
                createdByIp,
                attributes instanceof SequencedMap ? (SequencedMap<String, Object>) attributes : new LinkedHashMap<>(attributes)
            );
        }
    }

    /**
     * For backward compatibility with code that expects getters.
     * These methods delegate to the record component accessors.
     */
    public String getId() {
        return id();
    }

    public String getName() {
        return name();
    }

    public String getFormat() {
        return format();
    }

    public String getContentType() {
        return contentType();
    }

    public long getSize() {
        return size();
    }

    public String getRepositoryName() {
        return repositoryName();
    }

    public String getContainingRepositoryName() {
        return containingRepositoryName();
    }

    public Date getBlobCreated() {
        return blobCreated();
    }

    public Date getBlobUpdated() {
        return blobUpdated();
    }

    public Date getLastDownloaded() {
        return lastDownloaded();
    }

    public String getBlobRef() {
        return blobRef();
    }

    public String getComponentId() {
        return componentId();
    }

    public String getCreatedBy() {
        return createdBy();
    }

    public String getCreatedByIp() {
        return createdByIp();
    }

    public Map<String, Object> getAttributes() {
        return attributes();
    }
    
    /**
     * Example of how to use this record with pattern matching in Java 21.
     * This method demonstrates how consumers can leverage record patterns.
     * 
     * @param asset The asset to extract information from
     * @return A formatted string with key asset information
     */
    public static String formatAssetInfo(Object asset) {
        return switch (asset) {
            case AssetXO(String id, String name, String format, var contentType, var size, var repo, var _, var _, var _, var _, var _, var _, var _, var _, var _) ->
                String.format("Asset %s: %s (%s) in %s repository, %d bytes", id, name, format, repo, size);
            default -> "Not an AssetXO";
        };
    }
}