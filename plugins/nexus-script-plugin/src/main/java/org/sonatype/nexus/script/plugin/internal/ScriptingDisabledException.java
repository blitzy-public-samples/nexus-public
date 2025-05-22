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
package org.sonatype.nexus.script.plugin.internal;

import static java.lang.StringTemplate.STR;

/**
 * An action on a script has been attempted when scripting is disabled.
 * <p>
 * Updated for Java 21 to use String Templates for message formatting.
 *
 * @since 3.22
 */
public class ScriptingDisabledException extends RuntimeException
{
  private static final long serialVersionUID = 1L;
  /**
   * Constructs a new exception with the specified detail message.
   * Maintained for backward compatibility.
   *
   * @param message the detail message
   */
  public ScriptingDisabledException(final String message) {
    super(message);
  }
  
  /**
   * Constructs a new exception with a detail message formatted using Java 21 String Templates.
   * This constructor leverages Java 21's String Templates feature for more readable and
   * maintainable error message formatting.
   * 
   * @param action the action that was attempted
   * @param reason the reason why the action is not allowed
   */
  public ScriptingDisabledException(final String action, final String reason) {
    super(STR."Action '\{action}' cannot be performed: \{reason}");
  }
}