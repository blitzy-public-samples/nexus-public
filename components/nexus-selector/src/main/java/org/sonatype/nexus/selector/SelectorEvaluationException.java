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
package org.sonatype.nexus.selector;

import static java.lang.StringTemplate.STR;

/**
 * Represents runtime exceptions from evaluation an otherwise valid selector expression.
 * 
 * @since 3.1
 */
public class SelectorEvaluationException
    extends Exception
{
  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message
   */
  public SelectorEvaluationException(String message) {
    super(message);
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public SelectorEvaluationException(String message, Exception cause) {
    super(message, cause);
  }
  
  /**
   * Constructs a new exception with a detail message created from a template and parameters.
   * Uses Java 21 String Templates for more descriptive error messages.
   *
   * @param template the message template
   * @param params the parameters to be interpolated in the template
   * @since 3.60
   */
  public SelectorEvaluationException(StringTemplate template, Object... params) {
    super(STR."\{template}");
  }
  
  /**
   * Constructs a new exception with a detail message created from a template and parameters,
   * along with a cause exception.
   * Uses Java 21 String Templates for more descriptive error messages.
   *
   * @param template the message template
   * @param cause the cause of the exception
   * @param params the parameters to be interpolated in the template
   * @since 3.60
   */
  public SelectorEvaluationException(StringTemplate template, Exception cause, Object... params) {
    super(STR."\{template}", cause);
  }
  
  /**
   * Creates a new exception with a formatted message using Java 21 String Templates.
   * This factory method provides a convenient way to create exceptions with descriptive messages.
   *
   * @param selector the selector expression that caused the exception
   * @param reason the reason for the evaluation failure
   * @return a new exception with a formatted message
   * @since 3.60
   */
  public static SelectorEvaluationException forSelector(String selector, String reason) {
    return new SelectorEvaluationException(STR."Failed to evaluate selector '\{selector}': \{reason}");
  }
  
  /**
   * Creates a new exception with a formatted message using Java 21 String Templates,
   * including the cause exception.
   * This factory method provides a convenient way to create exceptions with descriptive messages.
   *
   * @param selector the selector expression that caused the exception
   * @param reason the reason for the evaluation failure
   * @param cause the underlying cause of the exception
   * @return a new exception with a formatted message and cause
   * @since 3.60
   */
  public static SelectorEvaluationException forSelector(String selector, String reason, Exception cause) {
    return new SelectorEvaluationException(STR."Failed to evaluate selector '\{selector}': \{reason}", cause);
  }
}