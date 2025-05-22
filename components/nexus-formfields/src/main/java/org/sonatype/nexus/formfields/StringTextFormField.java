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

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Single-line text field with enhanced Java 21 validation capabilities.
 */
public class StringTextFormField
    extends AbstractFormField<String>
{
  public StringTextFormField(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  public StringTextFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public StringTextFormField(String id) {
    super(id);
  }

  public String getType() {
    return "string";
  }

  public StringTextFormField withInitialValue(final String initialValue) {
    setInitialValue(initialValue);
    return this;
  }
  
  /**
   * Validates the input against the regex pattern and returns a validation message using String Templates.
   * 
   * @param input The input to validate
   * @return Validation message or null if valid
   */
  public String validateRegex(final String input) {
    String regexValidation = getRegexValidation();
    if (regexValidation == null || input == null) {
      return null;
    }
    
    try {
      if (!Pattern.compile(regexValidation).matcher(input).matches()) {
        return STR."Value \{input} does not match the required pattern: \{regexValidation}";
      }
      return null;
    }
    catch (PatternSyntaxException e) {
      return STR."Invalid regex pattern: \{e.getMessage()}";
    }
  }
  
  /**
   * Creates a new StringTextFormField with the specified regex validation pattern.
   * Uses improved Java 21 regex handling with validation.
   *
   * @param regexValidation The regex pattern to validate against
   * @return This field instance for fluent API usage
   * @throws PatternSyntaxException If the regex pattern is invalid
   */
  public StringTextFormField withRegexValidation(final String regexValidation) {
    // Validate the pattern syntax before setting it
    try {
      if (regexValidation != null) {
        Pattern.compile(regexValidation);
      }
      setRegexValidation(regexValidation);
      return this;
    }
    catch (PatternSyntaxException e) {
      throw new PatternSyntaxException(
          STR."Invalid regex pattern for field '\{getId()}': \{e.getMessage()}", 
          e.getPattern(), 
          e.getIndex());
    }
  }
  
  /**
   * Validates if the input matches the field's regex pattern.
   * 
   * @param input The input to validate
   * @return true if the input is valid or no regex pattern is set
   */
  public boolean isValidInput(final String input) {
    String regexValidation = getRegexValidation();
    if (regexValidation == null || input == null) {
      return true;
    }
    
    try {
      return Pattern.compile(regexValidation).matcher(input).matches();
    }
    catch (PatternSyntaxException e) {
      // Log the error using String Templates for better readability
      String errorMessage = STR."Invalid regex pattern for field '\{getId()}': \{e.getMessage()}";
      // In a real implementation, this would use a proper logger
      System.err.println(errorMessage);
      return false;
    }
  }
}