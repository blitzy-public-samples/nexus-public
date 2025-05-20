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

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.queryparser.classic.QueryParserBase;
import org.elasticsearch.index.query.QueryBuilder;

/**
 * Support for {@link ElasticSearchContribution} implementations.
 *
 * @since 3.15
 */
public class ElasticSearchContributionSupport
    implements ElasticSearchContribution
{

  @Override
  public void contribute(final Consumer<QueryBuilder> query, final String type, final String value) {
    // do nothing
  }

  /**
   * Escapes special characters in Lucene query strings while preserving certain characters that should remain unescaped.
   * Uses Java 21 String Templates and Pattern Matching for improved readability and maintainability.
   *
   * @param value the string to escape
   * @return the escaped string, or null if the input was null
   */
  public String escape(final String value) {
    if (null == value) {
      return null;
    }

    String escaped = QueryParserBase.escape(value);
    
    // Use pattern matching to determine which characters to unescape
    return switch (StringUtils.countMatches(value, "\"") % 2) {
      case 0 -> {
        // Even number of double quotes - unescape ?, *, and "
        String regex = STR."\\\\([?*\"])";
        yield escaped.replaceAll(regex, "$1");
      }
      default -> {
        // Odd number of double quotes - only unescape ? and *
        String regex = STR."\\\\([?*])";
        yield escaped.replaceAll(regex, "$1");
      }
    };
  }
}