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

import javax.annotation.Nullable;
import java.util.regex.Pattern;

/**
 * A text area form field allowing input for large amount of texts in a multi-line fashion.
 *
 * @since 2.0
 */
public class TextAreaFormField
    extends AbstractFormField<String>
{
  public TextAreaFormField(String id, String label, String helpText, boolean required, String regexValidation, boolean readOnly) {
    super(id, label, helpText, required, regexValidation);
    this.setReadOnly(readOnly);
  }

  public TextAreaFormField(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  public TextAreaFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public TextAreaFormField(String id) {
    super(id);
  }

  public String getType() {
    return "text-area";
  }

  public TextAreaFormField withInitialValue(final String initialValue) {
    setInitialValue(initialValue);
    return this;
  }
  
  /**
   * Generates a validation message for this field based on the provided value.
   * Uses Java 21 String Templates for improved readability and maintainability.
   *
   * @param value The value to validate
   * @return A validation message if validation fails, or null if validation passes
   * @since 3.60
   */
  @Nullable
  public String getValidationMessage(@Nullable final String value) {
    // Check if field is required but value is empty
    if (isRequired() && (value == null || value.trim().isEmpty())) {
      return STR."Field \{getLabel()} is required";
    }
    
    // Check if there's a regex validation pattern and the value doesn't match it
    String regex = getRegexValidation();
    if (regex != null && value != null && !value.isEmpty()) {
      try {
        if (!Pattern.compile(regex).matcher(value).matches()) {
          return STR."\{getLabel()} must match pattern: \{regex}";
        }
      } catch (Exception e) {
        return STR."Invalid validation pattern for \{getLabel()}: \{e.getMessage()}";
      }
    }
    
    return null; // Validation passed
  }
}