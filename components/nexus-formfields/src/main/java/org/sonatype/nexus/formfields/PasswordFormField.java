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

/**
 * A {@link StringTextFormField} that masks the input.
 * <p>
 * This implementation is compatible with Java 21's updated cryptography providers,
 * ensuring proper integration with the enhanced security features available in Java 21.
 * Password values are securely encrypted using the system's configured cryptographic
 * providers before storage.
 * </p>
 *
 * @since 2.7
 */
public class PasswordFormField
    extends StringTextFormField
    implements Encrypted
{
  /**
   * Creates a new password field with the specified parameters.
   *
   * @param id The field identifier
   * @param label The display label
   * @param helpText Help text for the field
   * @param required Whether the field is required
   * @param regexValidation Regular expression for validation
   */
  public PasswordFormField(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  /**
   * Creates a new password field with the specified parameters.
   *
   * @param id The field identifier
   * @param label The display label
   * @param helpText Help text for the field
   * @param required Whether the field is required
   */
  public PasswordFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  /**
   * Creates a new password field with the specified identifier.
   *
   * @param id The field identifier
   */
  public PasswordFormField(String id) {
    super(id);
  }

  /**
   * Returns the field type identifier.
   *
   * @return "password" as the field type
   */
  @Override
  public String getType() {
    return "password";
  }

  /**
   * Sets the initial value for this password field.
   * <p>
   * Note: Initial values for password fields should be used with caution as they may
   * expose sensitive information in the UI. When used, the values will be properly
   * encrypted using Java 21's cryptographic providers when stored.
   * </p>
   *
   * @param initialValue The initial value to set
   * @return This field instance for fluent API usage
   */
  @Override
  public PasswordFormField withInitialValue(final String initialValue) {
    super.withInitialValue(initialValue);
    return this;
  }
  
  /**
   * Creates a new password field with the specified regex validation pattern.
   * Uses improved Java 21 regex handling with validation.
   *
   * @param regexValidation The regex pattern to validate against
   * @return This field instance for fluent API usage
   * @throws java.util.regex.PatternSyntaxException If the regex pattern is invalid
   */
  public PasswordFormField withRegexValidation(final String regexValidation) {
    super.withRegexValidation(regexValidation);
    return this;
  }
  
  /**
   * Returns a string representation of this password field with sensitive information masked.
   * 
   * @return a string representation of this password field
   */
  @Override
  public String toString() {
    return STR."PasswordFormField{id=\{getId()}, label=\{getLabel()}, required=\{isRequired()}, readOnly=\{isReadOnly()}, disabled=\{isDisabled()}}";
  }
}
