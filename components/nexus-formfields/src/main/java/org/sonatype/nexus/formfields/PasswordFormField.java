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
 * This field implements the {@link Encrypted} interface, indicating that its value should be stored
 * in an encrypted format. The encryption is handled by the security framework using Java 21 compatible
 * cryptography providers.
 * <p>
 * Security Note: Password fields should always be used for sensitive information that requires encryption
 * at rest. The field masks input in the UI and ensures proper handling through the security subsystem.
 *
 * @since 2.7
 */
public class PasswordFormField
    extends StringTextFormField
    implements Encrypted
{
  /**
   * Creates a new password field with the specified properties.
   *
   * @param id              unique identifier for this field
   * @param label           display label for this field
   * @param helpText        help text for this field
   * @param required        whether this field is required
   * @param regexValidation regular expression used to validate the field's value
   */
  public PasswordFormField(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  /**
   * Creates a new password field with the specified properties.
   *
   * @param id       unique identifier for this field
   * @param label    display label for this field
   * @param helpText help text for this field
   * @param required whether this field is required
   */
  public PasswordFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  /**
   * Creates a new password field with the specified ID.
   *
   * @param id unique identifier for this field
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
   * Sets the initial value for this field and returns this instance for method chaining.
   * <p>
   * Note: Initial values for password fields should be used with caution as they may expose
   * sensitive information in certain contexts.
   *
   * @param initialValue the initial value to set
   * @return this instance for method chaining
   */
  @Override
  public PasswordFormField withInitialValue(final String initialValue) {
    super.withInitialValue(initialValue);
    return this;
  }
}