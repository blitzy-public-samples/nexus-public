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
import javax.validation.constraints.NotEmpty;

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
}