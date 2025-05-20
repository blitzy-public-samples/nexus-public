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

/**
 * Indicates how the column should be queried in SQL operations. This enum supports Java 21's pattern matching
 * for switch expressions, allowing for type-safe query selection.
 * 
 * For example, with pattern matching in Java 21:
 * {@code 
 * return switch(queryType) {
 *   case DEFAULT_TEXT_QUERY -> STR."Using standard operators: \{column} = ?";
 *   case FULL_TEXT_SEARCH_QUERY -> STR."Using full text search: \{column} @@ to_tsvector('simple', ?)";
 * };
 * }
 *
 * @see org.sonatype.nexus.repository.rest.SearchFieldSupport
 * @since Java 21
 */
public enum TextualQueryType
{
  /**
   * Standard text column querying approach using equality operators.
   * 
   * Example usage with String Templates in Java 21:
   * {@code STR."SQL query using \{column} = ? OR \{column} IN (?, ?, ?)"}
   * 
   * Compatible with both PostgreSQL and H2 database drivers in Java 21.
   */
  DEFAULT_TEXT_QUERY("standard", false),

  /**
   * Full text search query approach for advanced text searching capabilities.
   * 
   * For PostgreSQL: Uses PostgreSQL's tsquery() function with Java 21's improved JDBC driver support
   * Example: {@code STR."\{column} @@ to_tsvector('simple', ?)"}
   * 
   * For H2: Uses H2's ARRAY type column with compatibility mode
   * Example: {@code STR."\{column} = ARRAY[?]"}
   * 
   * Note: When using with Java 21's Virtual Threads, both PostgreSQL (42.7.2+) and 
   * H2 (2.2.224+) drivers support concurrent operations with improved performance.
   */
  FULL_TEXT_SEARCH_QUERY("fulltext", true);
  
  private final String queryMode;
  private final boolean supportsAdvancedSearch;
  
  /**
   * Constructor for TextualQueryType enum.
   * 
   * @param queryMode the string representation of the query mode
   * @param supportsAdvancedSearch whether this query type supports advanced search capabilities
   */
  TextualQueryType(String queryMode, boolean supportsAdvancedSearch) {
    this.queryMode = queryMode;
    this.supportsAdvancedSearch = supportsAdvancedSearch;
  }
  
  /**
   * Returns the string representation of the query mode.
   * Useful for logging and debugging with Java 21's String Templates.
   * 
   * @return the query mode as a string
   */
  public String getQueryMode() {
    return queryMode;
  }
  
  /**
   * Indicates whether this query type supports advanced search capabilities.
   * 
   * @return true if advanced search is supported, false otherwise
   */
  public boolean supportsAdvancedSearch() {
    return supportsAdvancedSearch;
  }
  
  /**
   * Determines if this query type is compatible with the specified database type.
   * Optimized for Java 21's pattern matching in switch expressions.
   * 
   * @param databaseType the database type to check compatibility with
   * @return true if compatible, false otherwise
   */
  public boolean isCompatibleWith(String databaseType) {
    return switch(this) {
      case DEFAULT_TEXT_QUERY -> true; // Compatible with all database types
      case FULL_TEXT_SEARCH_QUERY -> "postgresql".equalsIgnoreCase(databaseType) || 
                                     "h2".equalsIgnoreCase(databaseType);
    };
  }
  
  /**
   * Returns the appropriate SQL operator for this query type based on the database type.
   * Designed for use with Java 21's pattern matching for switch expressions.
   * 
   * @param databaseType the database type (e.g., "postgresql", "h2")
   * @return the SQL operator string appropriate for the database
   */
  public String getSqlOperator(String databaseType) {
    return switch(this) {
      case DEFAULT_TEXT_QUERY -> "=";
      case FULL_TEXT_SEARCH_QUERY -> switch(databaseType.toLowerCase()) {
        case "postgresql" -> "@@";
        case "h2" -> "=";
        default -> throw new IllegalArgumentException(STR."Unsupported database type: \{databaseType}");
      };
    };
  }
}