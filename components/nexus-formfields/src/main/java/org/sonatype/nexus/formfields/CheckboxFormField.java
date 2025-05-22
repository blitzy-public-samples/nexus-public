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

/**
 * Checkbox field.
 * 
 * @since 3.0
 */
public class CheckboxFormField
    extends AbstractFormField<Boolean>
{
  /**
   * Constructor.
   * 
   * @param id identifier of field
   * @param label label of field
   * @param helpText help text of field
   * @param required flag indicating if field is required
   */
  public CheckboxFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  /**
   * Constructor.
   * 
   * @param id identifier of field
   */
  public CheckboxFormField(String id) {
    super(id);
  }

  /**
   * Returns the type of the form field.
   * 
   * @return the type identifier
   */
  @Override
  public String getType() {
    return "checkbox";
  }

  /**
   * Sets the initial value of the field.
   * 
   * @param initialValue the initial value
   * @return this instance for method chaining
   */
  public CheckboxFormField withInitialValue(final Boolean initialValue) {
    setInitialValue(initialValue);
    return this;
  }
  
  /**
   * Validates the given value against field constraints.
   * 
   * @param value the value to validate
   * @return validation error message or null if valid
   */
  @Nullable
  public String validate(final Boolean value) {
    if (isRequired() && value == null) {
      return STR."Field \{getId()} is required but no value was provided";
    }
    return null;
  }
  
  /**
   * Returns a string representation of this field for debugging purposes.
   * 
   * @return string representation of this field
   */
  @Override
  public String toString() {
    return STR."CheckboxFormField[id=\{getId()}, label=\{getLabel()}, required=\{isRequired()}, initialValue=\{getInitialValue()}]";
  }
}
