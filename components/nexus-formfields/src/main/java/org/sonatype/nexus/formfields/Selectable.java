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
package org.sonatype.nexus.formfields;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * Implemented by {@link FormField}s whose value should be selected from a data store.
 *
 * The data store should return collections of records that have "id" and "name" fields.
 *
 * <p>
 * Implementation considerations for Java 21:
 * <ul>
 *   <li>Implementations should leverage Virtual Threads for remote data fetching operations to improve
 *       scalability and responsiveness. Use {@code Executors.newVirtualThreadPerTaskExecutor()} for
 *       I/O-bound operations instead of traditional thread pools.</li>
 *   <li>When fetching data from remote sources, prefer non-blocking I/O approaches to maximize the
 *       benefits of Virtual Threads. This allows thousands of concurrent operations with minimal
 *       resource overhead.</li>
 *   <li>Consider using structured concurrency patterns when implementing complex data fetching
 *       operations that require multiple steps or sources.</li>
 * </ul>
 * </p>
 *
 * @since 2.7
 */
public interface Selectable
{
  /**
   * Returns Ext.Direct API name used to configure Ext proxy.
   *
   * E.g. "coreui_RepositoryTarget.read"
   *
   * <p>
   * When implementing store API endpoints, consider using Virtual Threads for handling
   * concurrent requests, especially for I/O-bound operations like database queries or
   * remote service calls. This improves scalability without increasing resource consumption.
   * </p>
   *
   * @since 3.0
   */
  String getStoreApi();

  /**
   * Returns filters to be applied to store.
   * 
   * <p>
   * When processing filters in implementations, consider using Java 21 Pattern Matching
   * for more concise and type-safe filter handling. For complex filter processing that involves
   * I/O operations, leverage Virtual Threads to maintain responsiveness.
   * </p>
   *
   * @return Filters to be applied to store
   * @since 3.0
   */
  @Nullable
  Map<String, String> getStoreFilters();

  /**
   * Returns the name of the property that should be considered as a record id. Defaults to "id".
   * 
   * <p>
   * When implementing record mapping logic, consider using Java 21 Record Patterns for
   * more concise and type-safe data extraction from structured records.
   * </p>
   */
  String getIdMapping();

  /**
   * Returns the name of the property that should be considered as a record description. Defaults to "name".
   * 
   * <p>
   * When implementing record mapping logic, consider using Java 21 Record Patterns for
   * more concise and type-safe data extraction from structured records.
   * </p>
   */
  String getNameMapping();

}