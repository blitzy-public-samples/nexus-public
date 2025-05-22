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
package org.sonatype.nexus.selector;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.apache.commons.lang.StringUtils.isAlphanumeric;

/**
 * Builder of SQL 'where' clauses for content selectors.
 * 
 * This class has been updated to use Java 21 features including:
 * - Record Patterns for more concise data handling
 * - String Templates for improved readability
 * - Enhanced encapsulation for better security
 *
 * @since 3.16
 */
public class SelectorSqlBuilder
{
  /**
   * Record representing a property name and its alias.
   * Used for pattern matching in property alias operations.
   * 
   * Records provide a concise way to model immutable data with automatic
   * implementations of equals(), hashCode(), and toString().
   *
   * @since 3.60
   */
  private record PropertyAlias(String name, String alias) {
    /**
     * Validates that the name is not null and the alias is not null.
     */
    private PropertyAlias {
      checkNotNull(name, "Property name cannot be null");
      checkNotNull(alias, "Property alias cannot be null");
    }
  }

  protected final StringBuilder queryBuilder = new StringBuilder();

  private final Map<String, String> queryParameters = new HashMap<>();

  private final Map<String, PropertyAlias> propertyAliases = new HashMap<>();

  private String propertyPrefix = "";

  private String parameterNamePrefix = "";

  private String parameterPrefix = "";

  private String parameterSuffix = "";

  /**
   * Aliases the given property name to a specific record field.
   */
  public SelectorSqlBuilder propertyAlias(final String name, final String alias) {
    propertyAliases.put(checkNotNull(name), new PropertyAlias(checkNotNull(name), checkNotNull(alias)));
    return this;
  }

  /**
   * Sets the record field prefix to use for non-aliased property names.
   */
  public SelectorSqlBuilder propertyPrefix(final String prefix) {
    propertyPrefix = checkNotNull(prefix);
    return this;
  }

  /**
   * Sets the unique prefix to use for generated parameter names.
   */
  public SelectorSqlBuilder parameterPrefix(final String prefix) {
    parameterPrefix = checkNotNull(prefix);
    return this;
  }

  /**
   * Sets the unique prefix to use for generated parameter names.
   */
  public SelectorSqlBuilder parameterNamePrefix(final String namePrefix) {
    parameterNamePrefix = checkNotNull(namePrefix);
    return this;
  }

  /**
   * Sets the unique suffix to use for generated parameter names.
   */
  public SelectorSqlBuilder parameterSuffix(final String suffix) {
    parameterSuffix = checkNotNull(suffix);
    return this;
  }

  /**
   * Appends the given property to the query, aliasing/prefixing it as necessary.
   */
  public void appendProperty(final String property) {
    PropertyAlias propertyAlias = propertyAliases.computeIfAbsent(property, p -> {
      checkArgument(isAlphanumeric(p));
      return new PropertyAlias(p, propertyPrefix + p);
    });
    
    // Using record pattern matching to extract the alias
    if (propertyAlias instanceof PropertyAlias(var name, var alias)) {
      queryBuilder.append(alias);
    }
  }

  /**
   * Appends the given literal to the query; storing it as a parameter under a generated name.
   * Uses String Templates for more readable parameter construction.
   */
  public void appendLiteral(final String literal) {
    String parameter = parameterNamePrefix + queryParameters.size();
    // Using String Template for more readable parameter construction
    queryBuilder.append(STR."{parameterPrefix}{parameter}{parameterSuffix}");
    queryParameters.put(parameter, literal);
  }

  /**
   * Appends the given operator to the query.
   * Uses String Templates for more readable operator formatting.
   */
  public void appendOperator(final String operator) {
    queryBuilder.append(STR." {operator} ");
  }

  /**
   * Appends the given expression to the query, nested inside brackets to preserve precedence.
   */
  public void appendExpression(final Runnable expression) {
    queryBuilder.append('(');
    expression.run();
    queryBuilder.append(')');
  }

  /**
   * Returns the query string built so far.
   */
  public String getQueryString() {
    return queryBuilder.toString();
  }

  /**
   * Returns the parameters stored so far.
   * 
   * @return An unmodifiable view of the query parameters map
   */
  public Map<String, String> getQueryParameters() {
    return Map.copyOf(queryParameters);
  }

  /**
   * Clears the query string and its parameters so this builder can be re-used to build a new query.
   *
   * Any configured aliases or prefixes are left in place.
   */
  public void clearQueryString() {
    queryBuilder.setLength(0);
    queryParameters.clear();
  }
}