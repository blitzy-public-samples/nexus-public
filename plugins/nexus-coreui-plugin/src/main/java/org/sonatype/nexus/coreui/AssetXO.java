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
import java.util.Map;
import java.util.SequencedMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import jakarta.validation.constraints.NotEmpty;

/**
 * Asset exchange object.
 * <p>
 * Implemented as a Java 21 Record for immutability and automatic generation of
 * constructors, accessors, equals(), hashCode(), and toString() methods.
 *
 * @since 3.0
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
   * Constructor with validation and default implementation for attributes using LinkedHashMap
   * which implements SequencedMap in Java 21.
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
    
    // If attributes is null, provide a default empty SequencedMap implementation
    if (attributes == null) {
      attributes = new LinkedHashMap<>();
    }
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   * Records automatically generate accessor methods with the same name as the component,
   * but some frameworks might expect the traditional getter naming pattern.
   *
   * @return the id of this asset
   */
  public String getId() {
    return id;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the name of this asset
   */
  public String getName() {
    return name;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the format of this asset
   */
  public String getFormat() {
    return format;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the content type of this asset
   */
  public String getContentType() {
    return contentType;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the size of this asset
   */
  public long getSize() {
    return size;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the repository name of this asset
   */
  public String getRepositoryName() {
    return repositoryName;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the containing repository name of this asset
   */
  public String getContainingRepositoryName() {
    return containingRepositoryName;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the blob created date of this asset
   */
  public Date getBlobCreated() {
    return blobCreated;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the blob updated date of this asset
   */
  public Date getBlobUpdated() {
    return blobUpdated;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the last downloaded date of this asset
   */
  public Date getLastDownloaded() {
    return lastDownloaded;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the blob reference of this asset
   */
  public String getBlobRef() {
    return blobRef;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the component id of this asset
   */
  public String getComponentId() {
    return componentId;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the created by value of this asset
   */
  public String getCreatedBy() {
    return createdBy;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the created by IP of this asset
   */
  public String getCreatedByIp() {
    return createdByIp;
  }
  
  /**
   * Compatibility method for code that expects traditional getter pattern.
   *
   * @return the attributes of this asset as a SequencedMap
   */
  public SequencedMap<String, Object> getAttributes() {
    return attributes;
  }
  
//--- Builder Class ---

  /**
   * Provides a static method to create a new Builder instance for AssetXO.
   *
   * @return A new Builder.
   */
  public static Builder builder() {
      return new Builder();
  }

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
      private SequencedMap<String, Object> attributes;

      // Private constructor to enforce usage of AssetXO.builder()
      private Builder() {
          // Initialize attributes to avoid null, the record constructor will handle it too
          this.attributes = new LinkedHashMap<>();
          // Other fields can remain null if they are optional and don't have default values
      }

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
          // Defensive copy for mutable Date object
          this.blobCreated = (blobCreated != null) ? (Date) blobCreated.clone() : null;
          return this;
      }

      public Builder blobUpdated(Date blobUpdated) {
          // Defensive copy for mutable Date object
          this.blobUpdated = (blobUpdated != null) ? (Date) blobUpdated.clone() : null;
          return this;
      }

      public Builder lastDownloaded(Date lastDownloaded) {
          // Defensive copy for mutable Date object
          this.lastDownloaded = (lastDownloaded != null) ? (Date) lastDownloaded.clone() : null;
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

      /**
       * Sets the entire attributes SequencedMap. Defensively copies the map.
       *
       * @param attributes The attributes map.
       * @return The builder instance.
       */
      public Builder attributes(SequencedMap<String, Object> attributes) {
          // Defensively copy the map to ensure immutability
          this.attributes = (attributes != null) ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
          return this;
      }

      /**
       * Adds a single attribute to the attributes SequencedMap.
       *
       * @param key The key of the attribute.
       * @param value The value of the attribute.
       * @return The builder instance.
       */
      public Builder addAttribute(String key, Object value) {
          if (this.attributes == null) {
              this.attributes = new LinkedHashMap<>();
          }
          this.attributes.put(key, value);
          return this;
      }

      /**
       * Builds the final AssetXO instance.
       * The validation within the record's compact constructor will handle
       * the Objects.requireNonNull checks, and Bean Validation annotations
       * will be processed if a validator is configured.
       *
       * @return A new AssetXO instance.
       */
      public AssetXO build() {
          // Ensure attributes is not null before passing to the record constructor
          if (this.attributes == null) {
              this.attributes = new LinkedHashMap<>();
          }
          return new AssetXO(
              id,
              name,
              format,
              contentType,
              size,
              repositoryName,
              containingRepositoryName,
              // Pass defensive copies of Date objects
              (blobCreated != null) ? (Date) blobCreated.clone() : null,
              (blobUpdated != null) ? (Date) blobUpdated.clone() : null,
              (lastDownloaded != null) ? (Date) lastDownloaded.clone() : null,
              blobRef,
              componentId,
              createdBy,
              createdByIp,
              attributes // This will be a LinkedHashMap (which implements SequencedMap)
          );
      }
  }
}