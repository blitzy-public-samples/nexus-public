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
package org.sonatype.nexus.extdirect;

import static java.lang.StringTemplate.STR;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Support for {@link DirectComponent} implementations.
 * 
 * <p>
 * This class provides logging support via Java 21 String Templates for improved readability and performance.
 * String Templates allow for more readable and efficient logging statements compared to traditional
 * string concatenation or parameterized logging.
 * </p>
 * 
 * <p>
 * Example usage in subclasses:
 * <pre>
 * // Instead of:
 * // log.debug("Processing request for component: {} with parameters: {}", componentId, params);
 * // Use:
 * log.debug(STR."Processing request for component: \{componentId} with parameters: \{params}");
 * 
 * // Instead of:
 * // log.info("Created {} with id: {} at {}", type, id, timestamp);
 * // Use:
 * log.info(STR."Created \{type} with id: \{id} at \{timestamp}");
 * 
 * // Instead of:
 * // if (log.isDebugEnabled()) {
 * //     log.debug("Complex calculation result: " + expensiveCalculation());
 * // }
 * // Use:
 * if (log.isDebugEnabled()) {
 *     var result = expensiveCalculation();
 *     log.debug(STR."Complex calculation result: \{result}");
 * }
 * </pre>
 * </p>
 *
 * @since 3.0
 */
public abstract class DirectComponentSupport
  extends ComponentSupport
  implements DirectComponent
{
  /**
   * Logs a debug message using Java 21 String Templates.
   * 
   * @param template The string template to log
   */
  protected void logDebug(StringTemplate template) {
    if (log.isDebugEnabled()) {
      log.debug(STR.process(template));
    }
  }

  /**
   * Logs an info message using Java 21 String Templates.
   * 
   * @param template The string template to log
   */
  protected void logInfo(StringTemplate template) {
    if (log.isInfoEnabled()) {
      log.info(STR.process(template));
    }
  }

  /**
   * Logs a warning message using Java 21 String Templates.
   * 
   * @param template The string template to log
   */
  protected void logWarn(StringTemplate template) {
    if (log.isWarnEnabled()) {
      log.warn(STR.process(template));
    }
  }

  /**
   * Logs an error message using Java 21 String Templates.
   * 
   * @param template The string template to log
   */
  protected void logError(StringTemplate template) {
    if (log.isErrorEnabled()) {
      log.error(STR.process(template));
    }
  }
}