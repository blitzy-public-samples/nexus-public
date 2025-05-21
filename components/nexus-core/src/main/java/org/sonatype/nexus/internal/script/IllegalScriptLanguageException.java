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
package org.sonatype.nexus.internal.script;

/**
 * A scripting language that is not supported has been attempted.
 *
 * @since 3.22
 */
public class IllegalScriptLanguageException
    extends RuntimeException
{
  /**
   * Constructs a new exception with the specified detail message.
   *
   * @param message the detail message
   */
  public IllegalScriptLanguageException(final String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message created using String Templates.
   *
   * @param language the unsupported script language
   * @param supportedLanguages the list of supported languages
   * @return the exception with formatted message
   */
  public static IllegalScriptLanguageException unsupportedLanguage(final String language, final String... supportedLanguages) {
    return new IllegalScriptLanguageException(
        STR."Unsupported script language: \{language}. Supported languages: \{String.join(", ", supportedLanguages)}");
  }

  /**
   * Constructs a new exception with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public IllegalScriptLanguageException(final String message, final Throwable cause) {
    super(message, cause);
  }

  /**
   * Constructs a new exception with a detail message created using String Templates and the specified cause.
   *
   * @param cause the cause of the exception
   * @param language the unsupported script language
   * @param supportedLanguages the list of supported languages
   * @return the exception with formatted message and cause
   */
  public static IllegalScriptLanguageException unsupportedLanguage(final Throwable cause, 
      final String language, final String... supportedLanguages) {
    return new IllegalScriptLanguageException(
        STR."Unsupported script language: \{language}. Supported languages: \{String.join(", ", supportedLanguages)}",
        cause);
  }
}