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
package org.sonatype.nexus.internal.status;

import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;

/**
 * Health check that indicates if the available JVM reported CPU count is below the recommended threshold.
 * <p>
 * The {@see Runtime#availableProcessors} API used to check the number of available processors may return different
 * results during the lifetime of the JVM, but we will always consider it not healthy if the CPU is dynamically reduced
 * below threshold.
 *
 * @since 3.17
 */
@Named("Available CPUs")
@Singleton
public class AvailableCpuHealthCheck
    extends HealthCheckComponentSupport
{
  /**
   * Minimum recommended CPU Count
   */
  static final int MIN_RECOMMENDED_CPU_COUNT = 4;

  private int minCpuCount;

  public AvailableCpuHealthCheck() {
    this(MIN_RECOMMENDED_CPU_COUNT);
  }

  @VisibleForTesting
  AvailableCpuHealthCheck(final int minCpuCount) {
    this.minCpuCount = minCpuCount;
  }

  @Override
  protected Result check() {
    // Direct access to Runtime.availableProcessors() for improved efficiency in Java 21
    int available = Runtime.getRuntime().availableProcessors();
    
    // Using Java 21 Pattern Matching with switch expression for threshold comparison
    return switch (Integer.valueOf(available)) {
      // Case with a guard pattern - when available is less than minCpuCount
      case Integer count when count < minCpuCount -> 
        // Using Java 21 String Templates for more readable message formatting
        Result.unhealthy(STR."The host system is allocating a maximum of \{available} cores to the application. A minimum of \{minCpuCount} is recommended.");
      
      // Default case - when available is greater than or equal to minCpuCount
      default -> 
        // Using Java 21 String Templates for more readable message formatting
        Result.healthy(STR."The host system is allocating a maximum of \{available} cores to the application.");
    };
  }
}