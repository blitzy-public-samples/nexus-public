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
package org.sonatype.nexus.security;

/**
 * Utility class for formatting error messages in a consistent way.
 * Updated to use Java 21 String templates for improved readability.
 */
public class ErrorMessageUtil {

  /**
   * Returns a formatted error message using Java 21 String templates.
   * 
   * @param message the error message to format
   * @return the formatted error message
   */
  public static String getFormattedMessage(String message) {
    return STR."ValidationErrorXO{id='*', message='\{message}'}";
  }
}