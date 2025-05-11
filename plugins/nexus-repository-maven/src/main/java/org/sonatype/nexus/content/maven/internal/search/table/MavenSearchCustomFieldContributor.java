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
package org.sonatype.nexus.content.maven.internal.search.table;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.search.sql.SearchCustomFieldContributor;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.search.sql.SearchRecord;

import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_ARTIFACT_ID;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_CLASSIFIER;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_EXTENSION;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_GROUP_ID;

/**
 * Maven implementation of {@link SearchCustomFieldContributor} that populates search records
 * with Maven-specific attributes.
 * 
 * This implementation leverages Java 21 features such as Pattern Matching for instanceof
 * and Record Patterns for cleaner data handling.
 */
@Singleton
@Named(Maven2Format.NAME)
public class MavenSearchCustomFieldContributor
    implements SearchCustomFieldContributor
{
  /**
   * Record representing Maven artifact attributes for cleaner data handling.
   * Leverages Java 21 Record feature for immutable data carriers.
   */
  private record MavenAttribute(String groupId, String artifactId, String baseVersion, String extension, String classifier) {
    /**
     * Factory method to create a MavenAttribute from a map of attributes.
     */
    static MavenAttribute from(Map<String, String> attributes) {
      return new MavenAttribute(
          attributes.get(P_GROUP_ID),
          attributes.get(P_ARTIFACT_ID),
          attributes.get(P_BASE_VERSION),
          attributes.get(P_EXTENSION),
          attributes.get(P_CLASSIFIER)
      );
    }
  }
  
  @Override
  public void populateSearchCustomFields(final SearchRecord searchTableData, final Asset asset) {

    Object formatAttributes = asset.attributes().get(Maven2Format.NAME);

    // Using Java 21 Pattern Matching for instanceof to simplify type checking and casting
    Map<String, String> attributes =
        formatAttributes instanceof Map<?, ?> map ? (Map<String, String>) map : Collections.emptyMap();

    Optional.ofNullable(attributes.get(P_BASE_VERSION))
        .map(MavenSearchCustomFieldContributor::preventTokenization)
        .ifPresent(searchTableData::addFormatFieldValue1);
    Optional.ofNullable(attributes.get(P_EXTENSION))
        .ifPresent(searchTableData::addFormatFieldValue2);
    Optional.ofNullable(attributes.get(P_CLASSIFIER))
        .ifPresent(searchTableData::addFormatFieldValue3);

    buildGavec(searchTableData, attributes);
  }

  private void buildGavec(final SearchRecord searchTableData, final Map<String, String> attributes) {
    searchTableData.addFormatFieldValue4(getMavenAttributes(attributes)
        .filter(Objects::nonNull)
        .map(MavenSearchCustomFieldContributor::preventTokenization)
        .collect(joining(" "))
    );
  }

  /**
   * Extracts Maven attributes from the attribute map using the MavenAttribute record.
   * Demonstrates Java 21 Record Pattern usage for cleaner data extraction.
   */
  private Stream<String> getMavenAttributes(final Map<String, String> attributes) {
    // Create a MavenAttribute record from the map and then extract its components
    MavenAttribute mavenAttr = MavenAttribute.from(attributes);
    return Stream.of(mavenAttr.groupId(), mavenAttr.artifactId(), mavenAttr.baseVersion(), 
                     mavenAttr.extension(), mavenAttr.classifier());
  }

  /**
   * Only used for maven G.A.BV.E.C and repository names.
   *
   * Tokenization is the essence of Postgres's Full Text Search and it generally results in more search results (not
   * less) unless you perform a more restrictive search. We should accept the tokenization behaviour of
   * Full text search in the majority of cases and should only prevent the tokenization if there's
   * no other way of performing a more restrictive search for that particular use case.
   *
   * Note: nexus doesn't allow G.A.BV.E.C or repository name to begin with a '/'.
   * Thus, it's ok to use '/' as a marker to prevent Full Text search tokenization.
   */
  static String preventTokenization(final String searchTerm) {
    return isBlank(searchTerm) ? "" : "/" + searchTerm;
  }
}