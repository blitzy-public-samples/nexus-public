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
package org.sonatype.nexus.common.text;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Utility class for working with Java 21 String Templates.
 * Provides consistent message formatting across the application.
 *
 * @since 3.60
 */
public final class StringTemplateSupport
{
  /**
   * Private constructor to prevent instantiation.
   */
  private StringTemplateSupport() {
    // empty
  }

  /**
   * Formats an error message using String Templates.
   * 
   * @param message The message template
   * @param args The arguments to be interpolated into the template
   * @return The formatted error message
   */
  public static String formatErrorMessage(String message, Object... args) {
    checkNotNull(message, "Message cannot be null");
    
    if (args == null || args.length == 0) {
      return message;
    }
    
    // Use the format method to handle the interpolation
    return format(message, args);
  }

  /**
   * Formats a repository incompatibility message using String Templates.
   * 
   * @param repositoryName The name of the repository
   * @param reason The reason for incompatibility
   * @return The formatted incompatibility message
   */
  public static String formatRepositoryIncompatibility(String repositoryName, String reason) {
    checkNotNull(repositoryName, "Repository name cannot be null");
    checkNotNull(reason, "Reason cannot be null");
    
    return STR."Repository '\{repositoryName}' is incompatible: \{reason}";
  }

  /**
   * Formats a general message using String Templates.
   * 
   * @param template The message template with placeholders in the form of {0}, {1}, etc.
   * @param args The arguments to replace the placeholders
   * @return The formatted message
   */
  public static String format(String template, Object... args) {
    checkNotNull(template, "Template cannot be null");
    
    if (args == null || args.length == 0) {
      return template;
    }
    
    // Replace {0}, {1}, etc. with actual values using String Templates
    String result = template;
    for (int i = 0; i < args.length; i++) {
      Object arg = args[i];
      String placeholder = "\{" + i + "\}";
      
      // Use String Templates for each replacement
      result = STR."\{result.replace(placeholder, arg == null ? "null" : arg.toString())}";
    }
    
    return result;
  }
}