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

import java.util.Map;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Support for {@link DirectComponent} implementations.
 * <p>
 * Provides utility methods for logging Ext.Direct related information using Java 21 String Templates.
 *
 * @since 3.0
 */
public abstract class DirectComponentSupport
  extends ComponentSupport
  implements DirectComponent
{
  /**
   * Logs a method invocation with parameters using String Templates.
   *
   * @param methodName the name of the method being invoked
   * @param params the parameters passed to the method
   */
  protected void logMethodInvocation(final String methodName, final Object... params) {
    if (log.isDebugEnabled()) {
      log.debug(STR."Method invoked: \{methodName} with \{params.length} parameter(s)");
      
      if (params.length > 0 && log.isTraceEnabled()) {
        for (int i = 0; i < params.length; i++) {
          log.trace(STR."  Parameter \{i}: \{params[i]}");
        }
      }
    }
  }

  /**
   * Logs a method result using String Templates.
   *
   * @param methodName the name of the method
   * @param result the result returned by the method
   */
  protected void logMethodResult(final String methodName, final Object result) {
    if (log.isDebugEnabled()) {
      String resultType = result != null ? result.getClass().getSimpleName() : "null";
      log.debug(STR."Method \{methodName} returned result of type \{resultType}");
      
      if (log.isTraceEnabled() && result != null) {
        log.trace(STR."  Result: \{result}");
      }
    }
  }

  /**
   * Logs an exception that occurred during method execution using String Templates.
   *
   * @param methodName the name of the method where the exception occurred
   * @param e the exception that was thrown
   */
  protected void logMethodException(final String methodName, final Exception e) {
    log.error(STR."Exception in method \{methodName}: \{e.getMessage()}", e);
  }

  /**
   * Logs form data submission using String Templates.
   *
   * @param formName the name of the form being submitted
   * @param formData the form data as a map
   */
  protected void logFormSubmission(final String formName, final Map<String, Object> formData) {
    if (log.isDebugEnabled()) {
      log.debug(STR."Form submitted: \{formName} with \{formData.size()} field(s)");
      
      if (log.isTraceEnabled()) {
        formData.forEach((key, value) -> {
          // Avoid logging sensitive information like passwords
          boolean isSensitive = key.toLowerCase().contains("password") || 
                              key.toLowerCase().contains("secret") || 
                              key.toLowerCase().contains("token");
          String displayValue = isSensitive ? "*****" : String.valueOf(value);
          log.trace(STR."  Field \{key}: \{displayValue}");
        });
      }
    }
  }
}