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
import javax.validation.constraints.NotBlank;

/**
 * Component exchange object.
 *
 * @since 3.6
 */
public record BrowseNodeXO(
    @NotBlank String id,
    @NotBlank String text,
    @NotBlank String type,
    boolean leaf,
    String componentId,
    String assetId,
    String packageUrl
) {
  /**
   * Custom equals method that only compares the id field.
   * This maintains compatibility with the previous implementation.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BrowseNodeXO other = (BrowseNodeXO) o;
    return Objects.equals(id, other.id);
  }

  /**
   * Custom hashCode method that only uses the id field.
   * This maintains compatibility with the previous implementation.
   */
  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
  
  /**
   * Creates a new BrowseNodeXO with the specified id.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param id the id to set
   * @return a new BrowseNodeXO with the updated id
   */
  public BrowseNodeXO withId(final String id) {
    return new BrowseNodeXO(id, this.text, this.type, this.leaf, this.componentId, this.assetId, this.packageUrl);
  }

  /**
   * Creates a new BrowseNodeXO with the specified text.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param text the text to set
   * @return a new BrowseNodeXO with the updated text
   */
  public BrowseNodeXO withText(final String text) {
    return new BrowseNodeXO(this.id, text, this.type, this.leaf, this.componentId, this.assetId, this.packageUrl);
  }

  /**
   * Creates a new BrowseNodeXO with the specified type.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param type the type to set
   * @return a new BrowseNodeXO with the updated type
   */
  public BrowseNodeXO withType(final String type) {
    return new BrowseNodeXO(this.id, this.text, type, this.leaf, this.componentId, this.assetId, this.packageUrl);
  }

  /**
   * Creates a new BrowseNodeXO with the specified leaf value.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param leaf the leaf value to set
   * @return a new BrowseNodeXO with the updated leaf value
   */
  public BrowseNodeXO withLeaf(final boolean leaf) {
    return new BrowseNodeXO(this.id, this.text, this.type, leaf, this.componentId, this.assetId, this.packageUrl);
  }

  /**
   * Creates a new BrowseNodeXO with the specified componentId.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param componentId the componentId to set
   * @return a new BrowseNodeXO with the updated componentId
   */
  public BrowseNodeXO withComponentId(final String componentId) {
    return new BrowseNodeXO(this.id, this.text, this.type, this.leaf, componentId, this.assetId, this.packageUrl);
  }

  /**
   * Creates a new BrowseNodeXO with the specified assetId.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param assetId the assetId to set
   * @return a new BrowseNodeXO with the updated assetId
   */
  public BrowseNodeXO withAssetId(final String assetId) {
    return new BrowseNodeXO(this.id, this.text, this.type, this.leaf, this.componentId, assetId, this.packageUrl);
  }

  /**
   * Creates a new BrowseNodeXO with the specified packageUrl.
   * This maintains the fluent API style of the previous implementation.
   *
   * @param packageUrl the packageUrl to set
   * @return a new BrowseNodeXO with the updated packageUrl
   */
  public BrowseNodeXO withPackageUrl(final String packageUrl) {
    return new BrowseNodeXO(this.id, this.text, this.type, this.leaf, this.componentId, this.assetId, packageUrl);
  }
}
