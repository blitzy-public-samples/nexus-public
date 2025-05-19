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
package org.sonatype.nexus.repository.content;

import org.apache.ibatis.annotations.Param;

/**
 * Interface for generating SQL statements based on query parameters.
 * <p>
 * This interface has been enhanced with Java 21 pattern matching capabilities
 * to provide more flexible and type-safe SQL generation.
 *
 * @param <P> the type of SQL query parameters
 */
public interface SqlGenerator<P extends SqlQueryParameters>
{
    /**
     * Generates a SQL SELECT statement based on the provided parameters.
     *
     * @param params the query parameters
     * @return the generated SQL statement
     */
    String generateSelectStatement(@Param("params") final P params);
    
    /**
     * Generates a SQL statement based on the type of parameters using pattern matching.
     * <p>
     * This method uses Java 21's pattern matching for instanceof to determine the
     * appropriate SQL generation strategy based on the parameter type.
     *
     * @param params the query parameters (can be any subtype of SqlQueryParameters)
     * @return the generated SQL statement
     */
    default String generateStatement(SqlQueryParameters params) {
        return switch (params) {
            case P p -> generateSelectStatement(p);
            case FilterParameters fp -> generateFilteredStatement(fp);
            case SortParameters sp -> generateSortedStatement(sp);
            case PagingParameters pp -> generatePagingStatement(pp);
            default -> throw new IllegalArgumentException("Unsupported parameter type: " + params.getClass().getName());
        };
    }
    
    /**
     * Generates a SQL statement with filtering applied.
     * <p>
     * This is a default implementation that can be overridden by implementations
     * to provide custom filtering logic.
     *
     * @param params the filter parameters
     * @return the generated SQL statement with filtering
     */
    default String generateFilteredStatement(FilterParameters params) {
        if (params instanceof P p) {
            return generateSelectStatement(p) + " WHERE " + params.getFilterClause();
        }
        throw new IllegalArgumentException("Unsupported filter parameter type");
    }
    
    /**
     * Generates a SQL statement with sorting applied.
     * <p>
     * This is a default implementation that can be overridden by implementations
     * to provide custom sorting logic.
     *
     * @param params the sort parameters
     * @return the generated SQL statement with sorting
     */
    default String generateSortedStatement(SortParameters params) {
        if (params instanceof P p) {
            return generateSelectStatement(p) + " ORDER BY " + params.getSortClause();
        }
        throw new IllegalArgumentException("Unsupported sort parameter type");
    }
    
    /**
     * Generates a SQL statement with paging applied.
     * <p>
     * This is a default implementation that can be overridden by implementations
     * to provide custom paging logic.
     *
     * @param params the paging parameters
     * @return the generated SQL statement with paging
     */
    default String generatePagingStatement(PagingParameters params) {
        if (params instanceof P p) {
            return generateSelectStatement(p) + " LIMIT " + params.getLimit() + " OFFSET " + params.getOffset();
        }
        throw new IllegalArgumentException("Unsupported paging parameter type");
    }
    
    /**
     * Marker interface for filter parameters.
     */
    interface FilterParameters extends SqlQueryParameters {
        String getFilterClause();
    }
    
    /**
     * Marker interface for sort parameters.
     */
    interface SortParameters extends SqlQueryParameters {
        String getSortClause();
    }
    
    /**
     * Marker interface for paging parameters.
     */
    interface PagingParameters extends SqlQueryParameters {
        int getLimit();
        int getOffset();
    }
}