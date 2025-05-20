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
package org.sonatype.nexus.repository.rest.sql;

import java.util.Objects;

import org.sonatype.nexus.repository.search.SortDirection;

/**
 * Fields available for search when using an SQL backend for search.
 * <p>
 * Optimized for Java 21 pattern matching in switch expressions and record patterns.
 */
public enum SearchField
{
  // Repository metadata fields
  /**
   * The format of the repository
   */
  FORMAT(FieldCategory.REPOSITORY),
  REPOSITORY_NAME(FieldCategory.REPOSITORY),

  // Component metadata fields
  COMPONENT_ID(FieldCategory.COMPONENT),
  COMPONENT_KIND(FieldCategory.COMPONENT),
  NAMESPACE(FieldCategory.COMPONENT),
  NAME(FieldCategory.COMPONENT),
  VERSION(FieldCategory.COMPONENT, SortDirection.DESC),
  PRERELEASE(FieldCategory.COMPONENT),
  TAGS(FieldCategory.COMPONENT),
  LAST_MODIFIED(FieldCategory.COMPONENT),

  /**
   * The paths associated with the assets
   */
  PATHS(FieldCategory.ASSET),
  KEYWORDS(FieldCategory.ASSET),

  /**
   * Checksums
   */
  MD5(FieldCategory.CHECKSUM),
  SHA1(FieldCategory.CHECKSUM),
  SHA256(FieldCategory.CHECKSUM),
  SHA512(FieldCategory.CHECKSUM),

  /**
   * Format-specific fields
   */
  FORMAT_FIELD_1(FieldCategory.FORMAT_SPECIFIC),
  FORMAT_FIELD_2(FieldCategory.FORMAT_SPECIFIC),
  FORMAT_FIELD_3(FieldCategory.FORMAT_SPECIFIC),
  FORMAT_FIELD_4(FieldCategory.FORMAT_SPECIFIC),
  FORMAT_FIELD_5(FieldCategory.FORMAT_SPECIFIC),
  FORMAT_FIELD_6(FieldCategory.FORMAT_SPECIFIC),
  FORMAT_FIELD_7(FieldCategory.FORMAT_SPECIFIC),

  // Asset metadata fields
  UPLOADERS(FieldCategory.ASSET),
  UPLOADER_IPS(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_1(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_2(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_3(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_4(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_5(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_6(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_7(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_8(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_9(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_10(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_11(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_12(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_13(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_14(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_15(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_16(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_17(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_18(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_19(FieldCategory.ASSET),
  ASSET_FORMAT_VALUE_20(FieldCategory.ASSET);

  /**
   * Categories of search fields to support pattern matching in switch expressions.
   * 
   * @since 3.60
   */
  public enum FieldCategory {
    REPOSITORY,
    COMPONENT,
    ASSET,
    CHECKSUM,
    FORMAT_SPECIFIC
  }

  private final FieldCategory category;
  private final SortDirection direction;

  /**
   * Creates a search field with default ASC sort direction.
   */
  SearchField() {
    this(FieldCategory.COMPONENT, SortDirection.ASC);
  }

  /**
   * Creates a search field with specified sort direction.
   */
  SearchField(final SortDirection direction) {
    this(FieldCategory.COMPONENT, direction);
  }
  
  /**
   * Creates a search field with specified category and default ASC sort direction.
   */
  SearchField(final FieldCategory category) {
    this(category, SortDirection.ASC);
  }

  /**
   * Creates a search field with specified category and sort direction.
   */
  SearchField(final FieldCategory category, final SortDirection direction) {
    this.category = Objects.requireNonNull(category, "Field category cannot be null");
    this.direction = Objects.requireNonNull(direction, "Sort direction cannot be null");
  }

  /**
   * Returns the sort direction for this field.
   */
  public SortDirection direction() {
    return direction;
  }
  
  /**
   * Returns the category of this field.
   * 
   * @since 3.60
   */
  public FieldCategory category() {
    return category;
  }
  
  /**
   * Returns true if this field belongs to the specified category.
   * Designed for use with Java 21 pattern matching in switch expressions.
   * 
   * @since 3.60
   */
  public boolean isCategory(FieldCategory category) {
    return this.category == category;
  }
}