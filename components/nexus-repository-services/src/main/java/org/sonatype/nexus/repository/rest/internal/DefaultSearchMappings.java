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
package org.sonatype.nexus.repository.rest.internal;

import java.util.List;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.rest.SearchMapping;
import org.sonatype.nexus.repository.rest.SearchMappings;
import org.sonatype.nexus.repository.rest.sql.SearchField;
import org.sonatype.nexus.repository.rest.sql.SearchField.FieldCategory;

import static java.lang.StringTemplate.STR;
import static java.util.List.of;
import static org.sonatype.nexus.repository.rest.sql.SearchField.*;
import static org.sonatype.nexus.repository.search.index.SearchConstants.IS_PRERELEASE_KEY;
import static org.sonatype.nexus.repository.search.index.SearchConstants.REPOSITORY_NAME;

/**
 * Default implementation of SearchMappings providing standard search field mappings.
 * 
 * @since 3.7
 */
@Named("default")
@Singleton
public class DefaultSearchMappings
    extends ComponentSupport
    implements SearchMappings
{
  // String constants defined using Java 21 String Templates for improved performance
  public static final String NAME_RAW = STR."name.raw";
  public static final String NAME_RAW_ALIAS = STR."name";
  public static final String GROUP_RAW = STR."group.raw";
  public static final String VERSION = STR."version";
  public static final String PRERELEASE = STR."prerelease";

  // Checksum attribute paths defined using String Templates
  private static final String MD5_ATTR = STR."assets.attributes.checksum.md5";
  private static final String SHA1_ATTR = STR."assets.attributes.checksum.sha1";
  private static final String SHA256_ATTR = STR."assets.attributes.checksum.sha256";
  private static final String SHA512_ATTR = STR."assets.attributes.checksum.sha512";

  // Using Java's built-in List.of() instead of Guava's ImmutableList for improved performance
  private static final List<SearchMapping> MAPPINGS = of(
      createMapping("q", "keyword", "Query by keyword", KEYWORDS, false),
      createMapping("repository", REPOSITORY_NAME, "Repository name", REPOSITORY_NAME),
      createMapping("format", "format", "Query by format", FORMAT),
      createMapping("group", GROUP_RAW, "Component group", NAMESPACE),
      createMapping(NAME_RAW_ALIAS, NAME_RAW, "Component name", NAME),
      createMapping(VERSION, VERSION, "Component version", VERSION),
      createMapping(PRERELEASE, IS_PRERELEASE_KEY, "Prerelease version flag", PRERELEASE),
      createMapping("md5", MD5_ATTR, "Specific MD5 hash of component's asset", MD5),
      createMapping("sha1", SHA1_ATTR, "Specific SHA-1 hash of component's asset", SHA1),
      createMapping("sha256", SHA256_ATTR, "Specific SHA-256 hash of component's asset", SHA256),
      createMapping("sha512", SHA512_ATTR, "Specific SHA-512 hash of component's asset", SHA512)
  );

  /**
   * Creates a SearchMapping with appropriate settings based on the field type.
   * Uses pattern matching to determine the correct configuration based on field category.
   */
  private static SearchMapping createMapping(String alias, String attribute, String description, SearchField field) {
    return createMapping(alias, attribute, description, field, true);
  }

  /**
   * Creates a SearchMapping with appropriate settings based on the field type.
   * Uses pattern matching to determine the correct configuration based on field category.
   */
  private static SearchMapping createMapping(
      String alias, String attribute, String description, SearchField field, boolean exactMatch) {
    
    // Use pattern matching to determine the appropriate SearchMapping configuration
    return switch (field.category()) {
      case ASSET -> new SearchMapping(alias, attribute, description, field, exactMatch, SearchMapping.FilterType.ASSET);
      case CHECKSUM -> new SearchMapping(alias, attribute, description, field, true, SearchMapping.FilterType.ASSET);
      default -> new SearchMapping(alias, attribute, description, field, exactMatch);
    };
  }

  @Override
  public Iterable<SearchMapping> get() {
    return MAPPINGS;
  }
}
