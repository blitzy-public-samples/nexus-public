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

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import org.apache.ibatis.annotations.Param;

/**
 * Adapter for SQL generation using SqlGenerator implementations.
 * <p>
 * This class has been enhanced with Java 21 features:
 * <ul>
 *   <li>Pattern Matching for more concise SQL generation logic</li>
 *   <li>Virtual Threads for concurrent SQL operations</li>
 *   <li>String Templates for more readable SQL logging</li>
 * </ul>
 */
public class SqlAdapter {
    
    private static final Logger LOGGER = Logger.getLogger(SqlAdapter.class.getName());
    
    private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = 
            Executors.newVirtualThreadPerTaskExecutor();
    
    /**
     * Generates a SQL SELECT statement using the provided generator and parameters.
     * <p>
     * This method uses pattern matching to handle different types of generators and parameters,
     * and leverages Virtual Threads for concurrent SQL operations when appropriate.
     *
     * @param generator the SQL generator to use
     * @param params the parameters for SQL generation
     * @return the generated SQL statement
     */
    public String select(final SqlGenerator<SqlQueryParameters> generator, @Param("params") final SqlQueryParameters params) {
        // Use pattern matching to handle different types of generators and parameters
        return switch (generator) {
            // Handle case when we have a specific type of generator that might benefit from concurrent execution
            case ComplexSqlGenerator complex when isComplexQuery(params) -> 
                executeWithVirtualThread(complex, params);
            
            // Default case for standard generators
            case SqlGenerator<?> gen -> {
                String sql = gen.generateSelectStatement(params);
                logSqlStatement(sql, params);
                yield sql;
            }
        };
    }
    
    /**
     * Executes SQL generation using Virtual Threads for concurrent operations.
     * 
     * @param generator the SQL generator to use
     * @param params the parameters for SQL generation
     * @return the generated SQL statement
     */
    private String executeWithVirtualThread(final SqlGenerator<SqlQueryParameters> generator, 
                                           final SqlQueryParameters params) {
        try {
            Future<String> future = VIRTUAL_THREAD_EXECUTOR.submit(() -> {
                String sql = generator.generateSelectStatement(params);
                logSqlStatement(sql, params);
                return sql;
            });
            return future.get();
        } catch (Exception e) {
            LOGGER.warning(STR."Error executing SQL generation with Virtual Thread: \{e.getMessage()}");
            // Fallback to synchronous execution
            String sql = generator.generateSelectStatement(params);
            logSqlStatement(sql, params);
            return sql;
        }
    }
    
    /**
     * Determines if a query is complex enough to benefit from concurrent execution.
     * 
     * @param params the query parameters
     * @return true if the query is complex, false otherwise
     */
    private boolean isComplexQuery(final SqlQueryParameters params) {
        // This is a placeholder implementation - in a real scenario, you would
        // analyze the parameters to determine if the query is complex enough
        // to benefit from concurrent execution
        return params instanceof ComplexSqlQueryParameters;
    }
    
    /**
     * Logs the generated SQL statement using String Templates for improved readability.
     * 
     * @param sql the SQL statement to log
     * @param params the parameters used to generate the SQL
     */
    private void logSqlStatement(final String sql, final SqlQueryParameters params) {
        // Using String Templates for more readable logging
        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            LOGGER.fine(STR."Generated SQL: \{sql}");
            LOGGER.fine(STR."Parameters: \{params}");
        }
    }
    
    /**
     * Marker interface for complex SQL query parameters that might benefit from concurrent execution.
     */
    public interface ComplexSqlQueryParameters extends SqlQueryParameters {
    }
    
    /**
     * Specialized SQL generator for complex queries.
     */
    public interface ComplexSqlGenerator extends SqlGenerator<SqlQueryParameters> {
    }
}