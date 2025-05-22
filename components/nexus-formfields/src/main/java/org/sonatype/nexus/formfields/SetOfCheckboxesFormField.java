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

import static java.lang.StringTemplate.STR;

/**
 * Set of checkboxes field.
 */
public class SetOfCheckboxesFormField
    extends AbstractFormField<Boolean>
{
  public SetOfCheckboxesFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public String getType() {
    return "setOfCheckboxes";
  }
  
  /**
   * Validates the input value against the field's requirements.
   * Uses Pattern Matching for switch to handle different types of input values.
   *
   * @param value The value to validate
   * @return A validation message if invalid, or null if valid
   */
  public String validate(Object value) {
    if (isRequired() && value == null) {
      return generateRequiredFieldMessage(getId());
    }
    
    return switch (value) {
      case Boolean b -> null; // Boolean values are always valid for this field
      case String s when s.isEmpty() && isRequired() -> generateRequiredFieldMessage(getId());
      case String s -> null; // Non-empty strings are valid
      case null -> null; // Already checked required above
      default -> generateInvalidTypeMessage(value);
    };
  }
  
  /**
   * Generates a validation message for required fields using String Templates.
   *
   * @param fieldId The ID of the field
   * @return A validation message
   */
  private String generateRequiredFieldMessage(String fieldId) {
    return STR."Field \{fieldId} is required";
  }
  
  /**
   * Generates a validation message for invalid input types using String Templates.
   *
   * @param value The invalid value
   * @return A validation message
   */
  private String generateInvalidTypeMessage(Object value) {
    String typeName = value.getClass().getSimpleName();
    return STR."Invalid type for checkbox field: \{typeName}. Expected Boolean.";
  }
}
