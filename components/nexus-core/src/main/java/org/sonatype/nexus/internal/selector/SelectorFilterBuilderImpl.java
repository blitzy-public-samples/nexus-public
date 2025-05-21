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
package org.sonatype.nexus.internal.selector;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import static java.lang.StringTemplate.STR;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.selector.JexlSelector;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorEvaluationException;
import org.sonatype.nexus.selector.SelectorFilterBuilder;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.selector.SelectorSqlBuilder;

import static java.util.Objects.requireNonNull;

@Named
@Singleton
public class SelectorFilterBuilderImpl
    extends ComponentSupport
    implements SelectorFilterBuilder
{
  public static String FILTER_PARAMS = "filterParams";

  private final SelectorManager selectorManager;

  @Inject
  public SelectorFilterBuilderImpl(final SelectorManager selectorManager) {
    this.selectorManager = requireNonNull(selectorManager);
  }

  @Nullable
  public String buildFilter(
      final String format,
      final String pathAlias,
      final List<SelectorConfiguration> selectors,
      final Map<String, Object> filterParameters)
  {
    // Using Java 21 Pattern Matching for switch to filter out JexlSelector types
    List<SelectorConfiguration> activeSelectors = selectors.stream()
        .filter(selector -> switch (selector.getType()) {
            case JexlSelector.TYPE -> false;
            default -> true;
        })
        .collect(Collectors.toList());

    if (activeSelectors.isEmpty()) {
      return null;
    }

    StringBuilder filterBuilder = new StringBuilder();

    if (activeSelectors.size() > 1) {
      filterBuilder.append('(');
    }

    SelectorSqlBuilder sqlBuilder = new SelectorSqlBuilder()
        .propertyAlias("path", pathAlias)
        .propertyAlias("format", "'" + format + "'")
        .parameterPrefix("#{" + FILTER_PARAMS + ".")
        .parameterSuffix("}");

    appendSelectors(filterBuilder, sqlBuilder, activeSelectors, filterParameters);

    if (activeSelectors.size() > 1) {
      filterBuilder.append(')');
    }

    return filterBuilder.toString();
  }

  public void appendSelectors(
      final StringBuilder filterBuilder,
      final SelectorSqlBuilder sqlBuilder,
      final List<SelectorConfiguration> selectors,
      final Map<String, Object> filterParameters)
  {
    int selectorCount = 0;

    for (SelectorConfiguration selector : selectors) {
      try {
        // Optimize SQL parameter handling with Java 21 features
        String paramPrefix = "s" + selectorCount + "p";
        sqlBuilder.parameterNamePrefix(paramPrefix);

        selectorManager.toSql(selector, sqlBuilder);

        if (selectorCount > 0) {
          filterBuilder.append(" or ");
        }

        // Build the SQL filter expression
        filterBuilder.append('(').append(sqlBuilder.getQueryString()).append(')');
        
        // Efficiently merge SQL parameters into the filter parameters map
        Map<String, Object> queryParams = sqlBuilder.getQueryParameters();
        filterParameters.putAll(queryParams);

        selectorCount++;
      }
      catch (SelectorEvaluationException e) {
        // Enhanced logging with String Templates for improved readability and debugging
        String selectorName = selector.getName();
        if (log.isDebugEnabled()) {
          log.warn(STR."Problem evaluating selector \{selectorName} as SQL: \{e.getMessage()}", e);
        } else {
          log.warn(STR."Problem evaluating selector \{selectorName} as SQL");
        }
      }
      finally {
        sqlBuilder.clearQueryString();
      }
    }
  }
}