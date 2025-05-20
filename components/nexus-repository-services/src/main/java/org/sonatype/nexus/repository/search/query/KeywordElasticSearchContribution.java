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
package org.sonatype.nexus.repository.search.query;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Named;
import javax.inject.Singleton;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;

/**
 * "Keyword" {@link ElasticSearchContribution} (adds filter as an ES query string).
 *
 * @since 3.15
 */
@Named(KeywordElasticSearchContribution.NAME)
@Singleton
public class KeywordElasticSearchContribution
    extends ElasticSearchContributionSupport
{
  public static final String NAME = "keyword";

  // Allow dependency searches of the form "group:name[:version][:extension][:classifier]"
  private Pattern dependencyPattern = Pattern.compile(
      "^(?<group>[^\\s:]+):(?<name>[^\\s:]+)(:(?<version>[^\\s:]+))?(:(?<extension>[^\\s:]+))?(:(?<classifier>[^\\s:]+))?$");

  /**
   * Record to represent Maven GAV coordinates extracted from a search query.
   * Uses Java 21 record pattern matching for improved type safety and readability.
   * 
   * @since 3.60
   */
  private record GavCoordinates(String group, String name, String version, String extension, String classifier) {}

  @Override
  public void contribute(final Consumer<QueryBuilder> query, final String type, final String value) {
    // Use pattern matching for switch to handle different value types
    switch (value) {
      case null -> {
        // No value provided, nothing to contribute
        return;
      }
      case String s -> {
        Matcher gavSearchMatcher = dependencyPattern.matcher(s.trim());
        
        if (gavSearchMatcher.matches()) {
          // Extract GAV components using record pattern
          GavCoordinates gav = new GavCoordinates(
              gavSearchMatcher.group("group"),
              gavSearchMatcher.group("name"),
              gavSearchMatcher.group("version"),
              gavSearchMatcher.group("extension"),
              gavSearchMatcher.group("classifier")
          );

          final BoolQueryBuilder gavQuery = QueryBuilders.boolQuery();

          // Build the query based on GAV components
          buildGavQuery(gavQuery, gav);

          query.accept(gavQuery);
        }
        else {
          // Handle as a regular keyword search
          String escaped = escape(s);
          QueryStringQueryBuilder keywordQuery = QueryBuilders.queryStringQuery(escaped)
              .field("name.case_insensitive")
              .field("group.case_insensitive")
              .field("_all");
          query.accept(keywordQuery);
        }
      }
    }
  }

  /**
   * Builds a GAV query using Java 21 pattern matching to handle the components.
   * Leverages record patterns for type-safe extraction of GAV components.
   * 
   * @param gavQuery the query builder to populate
   * @param gav the GAV coordinates record
   * @since 3.60
   */
  private void buildGavQuery(final BoolQueryBuilder gavQuery, final GavCoordinates gav) {
    // Use pattern matching to handle the GAV components
    switch (gav) {
      // Using a single case with pattern variables to extract all components at once
      case GavCoordinates(String group, String name, String version, String extension, String classifier) -> {
        // Add each component to the query when present
        if (group != null) {
          gavQuery.must(QueryBuilders.termQuery("group.raw", group));
        }
        
        if (name != null) {
          gavQuery.must(QueryBuilders.termQuery("name.raw", name));
        }
        
        if (version != null) {
          gavQuery.must(QueryBuilders.termQuery("version", version));
        }
        
        if (extension != null) {
          gavQuery.must(QueryBuilders.termQuery("assets.attributes.maven2.extension", extension));
        }
        
        if (classifier != null) {
          gavQuery.must(QueryBuilders.termQuery("assets.attributes.maven2.classifier", classifier));
        }
      }
    }
  }
}