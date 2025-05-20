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
package org.sonatype.nexus.security.config.memory;

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.security.config.CUser;

/**
 * An implementation of {@link CUser} suitable for an in-memory backing store.
 *
 * @since 3.0
 */
public class MemoryCUser
    implements CUser
{
  private String email;

  private String firstName;

  private String id;

  private String lastName;

  private String password;

  private String status;

  private int version;

  @Override
  public String getEmail() {
    return this.email;
  }

  @Override
  public String getFirstName() {
    return this.firstName;
  }

  @Override
  public String getId() {
    return this.id;
  }

  @Override
  public String getLastName() {
    return this.lastName;
  }

  @Override
  public String getPassword() {
    return this.password;
  }

  @Override
  public String getStatus() {
    return this.status;
  }

  @Override
  public int getVersion() {
    return version;
  }

  /**
   * Determines if the user is active using pattern matching for switch.
   * This implementation uses pattern matching to check if the status matches
   * either STATUS_ACTIVE or STATUS_CHANGE_PASSWORD.
   *
   * @return true if the user is active, false otherwise
   */
  @Override
  public boolean isActive() {
    return switch (status) {
      case STATUS_ACTIVE, STATUS_CHANGE_PASSWORD -> true;
      default -> false;
    };
  }

  @Override
  public void setEmail(final String email) {
    this.email = email;
  }

  @Override
  public void setFirstName(final String firstName) {
    this.firstName = firstName;
  }

  @Override
  public void setId(final String id) {
    this.id = id;
  }

  @Override
  public void setLastName(final String lastName) {
    this.lastName = lastName;
  }

  @Override
  public void setPassword(final String password) {
    this.password = password;
  }

  @Override
  public void setStatus(final String status) {
    this.status = status;
  }

  @Override
  public void setVersion(final int version) {
    this.version = version;
  }

  /**
   * Enhanced builder method for setting email with improved chaining.
   *
   * @param email the email to set
   * @return this instance for method chaining
   */
  public MemoryCUser withEmail(final String email) {
    this.email = email;
    return this;
  }

  /**
   * Enhanced builder method for setting firstName with improved chaining.
   *
   * @param firstName the firstName to set
   * @return this instance for method chaining
   */
  public MemoryCUser withFirstName(final String firstName) {
    this.firstName = firstName;
    return this;
  }

  /**
   * Enhanced builder method for setting id with improved chaining.
   *
   * @param id the id to set
   * @return this instance for method chaining
   */
  public MemoryCUser withId(final String id) {
    this.id = id;
    return this;
  }

  /**
   * Enhanced builder method for setting lastName with improved chaining.
   *
   * @param lastName the lastName to set
   * @return this instance for method chaining
   */
  public MemoryCUser withLastName(final String lastName) {
    this.lastName = lastName;
    return this;
  }

  /**
   * Enhanced builder method for setting password with improved chaining.
   *
   * @param password the password to set
   * @return this instance for method chaining
   */
  public MemoryCUser withPassword(final String password) {
    this.password = password;
    return this;
  }

  /**
   * Enhanced builder method for setting status with improved chaining.
   *
   * @param status the status to set
   * @return this instance for method chaining
   */
  public MemoryCUser withStatus(final String status) {
    this.status = status;
    return this;
  }

  /**
   * Enhanced builder method for setting version with improved chaining.
   *
   * @param version the version to set
   * @return this instance for method chaining
   */
  public MemoryCUser withVersion(final int version) {
    this.version = version;
    return this;
  }

  /**
   * Creates a clone of this user with improved type handling.
   * Uses pattern matching to handle the CloneNotSupportedException more elegantly.
   *
   * @return a clone of this user
   */
  @Override
  public MemoryCUser clone() {
    try {
      return (MemoryCUser) super.clone();
    }
    catch (Exception e) {
      // Use pattern matching to handle different exception types
      Throwable cause = switch (e) {
        case CloneNotSupportedException cnse -> cnse;
        case RuntimeException re when re.getCause() != null -> re.getCause();
        default -> e;
      };
      throw new RuntimeException("Failed to clone user: " + getId(), cause);
    }
  }

  /**
   * Returns a string representation of this user using Java 21 String Templates.
   * This implementation uses the STR processor for more readable and efficient string creation.
   *
   * @return a string representation of this user
   */
  @Override
  public String toString() {
    return STR."{getClass().getSimpleName()}{id='{id}', firstName='{firstName}', lastName='{lastName}', password='{Strings2.mask(password)}', status='{status}', email='{email}', version='{version}'}";
  }
}