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
package org.sonatype.nexus.systemchecks;

import com.codahale.metrics.health.HealthCheck;

/**
 * Base class for health checks that should only be applied under certain conditions.
 * <p>
 * Implementations should override {@link #shouldApply()} to determine if the health check
 * should be executed in the current environment.
 * <p>
 * When implementing health checks, it is recommended to use Java 21 String Templates for
 * improved logging and messaging. String Templates provide a more readable and maintainable
 * way to include dynamic values in messages.
 * <p>
 * Example usage with String Templates:
 * <pre>
 * {@code
 * // Using STR template processor for simple string interpolation
 * String componentName = "Database";
 * int connectionCount = 5;
 * String message = STR."\{componentName} health check found \{connectionCount} active connections";
 * 
 * // Using FMT template processor for formatted output
 * String formattedMessage = FMT."\{componentName} is at %2.1f\{loadFactor * 100}% capacity";
 * 
 * // In health check implementation
 * return Result.healthy(STR."\{componentName} is operating normally with \{connectionCount} connections");
 * // or for unhealthy results
 * return Result.unhealthy(STR."\{componentName} failed: \{errorMessage}");
 * }
 * </pre>
 * 
 * Benefits of using String Templates in health checks:
 * <ul>
 *   <li>Improved readability - variable names appear directly in the message context</li>
 *   <li>Better maintainability - easier to update messages with complex dynamic content</li>
 *   <li>Reduced errors - compile-time checking of template expressions</li>
 *   <li>Enhanced security - template processors can validate and sanitize content</li>
 * </ul>
 */
public abstract class ConditionallyAppliedHealthCheck
    extends HealthCheck
{
  /**
   * Determines whether this health check should be applied.
   * 
   * @return {@code true} if the health check should be applied, {@code false} otherwise
   */
  abstract public boolean shouldApply();
  
  /**
   * Creates a formatted health check message using String Templates.
   * <p>
   * This helper method demonstrates the recommended approach for creating
   * health check messages with dynamic content using Java 21 String Templates.
   *
   * @param component the component being checked
   * @param status the status message or description
   * @param details additional details about the health check (optional)
   * @return a formatted message string
   */
  protected String formatHealthMessage(String component, String status, String... details) {
    StringBuilder message = new StringBuilder(STR."\{component}: \{status}");
    
    if (details != null && details.length > 0) {
      message.append(" - Details:");
      for (int i = 0; i < details.length; i++) {
        message.append(STR."\n  [\{i+1}] \{details[i]}");
      }
    }
    
    return message.toString();
  }
}