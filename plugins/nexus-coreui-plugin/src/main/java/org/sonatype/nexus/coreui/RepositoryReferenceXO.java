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

import java.util.Objects;

/**
 * Repository reference exchange object.
 * 
 * Optimized for Java 21 with immutable fields and pattern matching support.
 *
 * @since 3.0
 */
public class RepositoryReferenceXO
    extends ReferenceXO
{
  private final String type;

  private final String format;

  private final String versionPolicy;

  private final String url;

  private final String blobStoreName;

  private final RepositoryStatusXO status;

  /**
   * sortOrder will override the typical alphanumeric ordering in the UI, so the higher your sortOrder, the closer to
   * the top you will get
   */
  private final int sortOrder;

  public RepositoryReferenceXO(
      final String id,
      final String name,
      final String type,
      final String format,
      final String versionPolicy,
      final String url,
      final String blobStoreName,
      final RepositoryStatusXO status,
      final int sortOrder)
  {
    setId(id);
    setName(name);
    this.type = type;
    this.format = format;
    this.versionPolicy = versionPolicy;
    this.url = url;
    this.status = status;
    this.blobStoreName = blobStoreName;
    this.sortOrder = sortOrder;
  }

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
   * Deconstruction pattern method to support pattern matching in Java 21.
   * Allows using this object with record patterns elsewhere in the codebase.
   */
  public record Components(
      String id,
      String name,
      String type,
      String format,
      String versionPolicy,
      String url,
      String blobStoreName,
      RepositoryStatusXO status,
      int sortOrder) {}

  /**
   * Returns the components of this object for use with pattern matching.
   */
  public Components components() {
    return new Components(
        getId(),
        getName(),
        type,
        format,
        versionPolicy,
        url,
        blobStoreName,
        status,
        sortOrder);
  }

  public String getType() {
    return type;
  }

  public String getFormat() {
    return format;
  }

  public String getVersionPolicy() {
    return versionPolicy;
  }

  public String getUrl() {
    return url;
  }

  public String getBlobStoreName() {
    return blobStoreName;
  }

  public RepositoryStatusXO getStatus() {
    return status;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    RepositoryReferenceXO that = (RepositoryReferenceXO) o;
    return sortOrder == that.sortOrder &&
        Objects.equals(type, that.type) &&
        Objects.equals(format, that.format) &&
        Objects.equals(versionPolicy, that.versionPolicy) &&
        Objects.equals(url, that.url) &&
        Objects.equals(blobStoreName, that.blobStoreName) &&
        Objects.equals(status, that.status);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), type, format, versionPolicy, url, blobStoreName, status, sortOrder);
  }

  @Override
  public String toString() {
    return STR."RepositoryReferenceXO [id=\{getId()}, name=\{getName()}, type=\{type}, format=\{format}, " +
        "versionPolicy=\{versionPolicy}, url=\{url}, blobStoreName=\{blobStoreName}, status=\{status}, " +
        "sortOrder=\{sortOrder}]"; 
  }
}