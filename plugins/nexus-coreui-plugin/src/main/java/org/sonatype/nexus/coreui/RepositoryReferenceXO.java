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

import javax.validation.constraints.NotBlank;

/**
 * Repository reference exchange object.
 * Refactored as a Java record for Java 21 compatibility.
 *
 * @since 3.0
 */
public record RepositoryReferenceXO(
    @NotBlank String id,
    @NotBlank String name,
    String type,
    String format,
    String versionPolicy,
    String url,
    String blobStoreName,
    RepositoryStatusXO status,
    int sortOrder)
{
  /**
   * Constructor with default sortOrder (0).
   */
  public RepositoryReferenceXO(
      final String id,
      final String name,
      final String type,
      final String format,
      final String versionPolicy,
      final String url,
      final String blobStoreName,
      final RepositoryStatusXO status)
  {
    this(id, name, type, format, versionPolicy, url, blobStoreName, status, 0);
  }
  
  /**
   * Returns the ID of this reference.
   * Provided for backward compatibility with ReferenceXO.
   * 
   * @return the ID
   */
  public String getId() {
    return id();
  }
  
  /**
   * Returns the name of this reference.
   * Provided for backward compatibility with ReferenceXO.
   * 
   * @return the name
   */
  public String getName() {
    return name();
  }
  
  /**
   * Returns the type of this repository reference.
   * 
   * @return the type
   */
  public String getType() {
    return type();
  }
  
  /**
   * Returns the format of this repository reference.
   * 
   * @return the format
   */
  public String getFormat() {
    return format();
  }
  
  /**
   * Returns the version policy of this repository reference.
   * 
   * @return the version policy
   */
  public String getVersionPolicy() {
    return versionPolicy();
  }
  
  /**
   * Returns the URL of this repository reference.
   * 
   * @return the URL
   */
  public String getUrl() {
    return url();
  }
  
  /**
   * Returns the blob store name of this repository reference.
   * 
   * @return the blob store name
   */
  public String getBlobStoreName() {
    return blobStoreName();
  }
  
  /**
   * Returns the status of this repository reference.
   * 
   * @return the status
   */
  public RepositoryStatusXO getStatus() {
    return status();
  }
  
  /**
   * Returns the sort order of this repository reference.
   * sortOrder will override the typical alphanumeric ordering in the UI, so the higher your sortOrder, the closer to
   * the top you will get
   * 
   * @return the sort order
   */
  public int getSortOrder() {
    return sortOrder();
  }
}