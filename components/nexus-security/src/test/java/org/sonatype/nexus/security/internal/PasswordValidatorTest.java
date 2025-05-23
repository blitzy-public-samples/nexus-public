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

package org.sonatype.nexus.security.internal;

import org.sonatype.nexus.rest.ValidationErrorsException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * @since 3.25
 */
public class PasswordValidatorTest
{
  @Test
  void testValidate_noValidation() {
    PasswordValidator underTest = new PasswordValidator(null, null);

    assertDoesNotThrow(() -> underTest.validate("foo"));
  }

  @Test
  void testValidate_passValidation() {
    PasswordValidator underTest = new PasswordValidator(".*", null);

    assertDoesNotThrow(() -> underTest.validate("foo"));
  }

  @Test
  void testValidate_failValidation() {
    PasswordValidator underTest = new PasswordValidator("[a]+", null);

    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("foo"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }

  @Test
  void testValidate_failValidationCustomErrorMessage() {
    PasswordValidator underTest = new PasswordValidator("[a]+", "Bad bad bad");

    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("foo"));
    assertEquals("Bad bad bad", exception.getMessage());
  }
  
  /**
   * Tests Java 21 pattern matching compatibility with regex patterns.
   */
  @Test
  void testValidate_java21PatternMatching_validPattern() {
    // Using a pattern that leverages Java 21 pattern matching features
    PasswordValidator underTest = new PasswordValidator("[a-z&&[^m-p]]+", null);
    
    // Should pass for a string that matches the pattern (lowercase letters except m-p)
    assertDoesNotThrow(() -> underTest.validate("abcdefghijklqrstuvwxyz"));
    
    // Should fail for a string that doesn't match the pattern
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("abcmno"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }
  
  /**
   * Tests Java 21 pattern matching with complex regex patterns.
   */
  @Test
  void testValidate_java21PatternMatching_complexPattern() {
    // Complex pattern with lookahead assertions (requires at least one digit, one lowercase, one uppercase)
    PasswordValidator underTest = new PasswordValidator(
        "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\\S+$).{8,}$", 
        "Password must contain at least one digit, one lowercase, one uppercase letter, and be at least 8 characters long");
    
    // Valid password that meets all criteria
    assertDoesNotThrow(() -> underTest.validate("Password123"));
    
    // Invalid password - test with different failures
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("password"));
    assertEquals("Password must contain at least one digit, one lowercase, one uppercase letter, and be at least 8 characters long", 
        exception.getMessage());
  }
  
  /**
   * Tests validation with Java 21 String Templates.
   */
  @Test
  void testValidate_withStringTemplates() {
    PasswordValidator underTest = new PasswordValidator("[a-z]+", null);
    
    // Using String Template to create the password
    String prefix = "test";
    int suffix = 123;
    
    // String Template syntax in Java 21
    String password = STR."\{prefix}\{suffix}";
    
    // Should fail because it contains digits
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate(password));
    assertEquals("Password does not match corporate policy", exception.getMessage());
    
    // Create a valid password with String Template
    String validPrefix = "valid";
    String validSuffix = "password";
    String validPassword = STR."\{validPrefix}\{validSuffix}";
    
    // Should pass because it's all lowercase letters
    assertDoesNotThrow(() -> underTest.validate(validPassword));
  }
  
  /**
   * Tests error message handling with ValidationErrorsException in Java 21.
   */
  @Test
  void testValidate_errorMessageHandling() {
    // Test with a custom error message containing special characters
    String customErrorMsg = "Password must match pattern: ^[a-z]+$ (lowercase letters only)";
    PasswordValidator underTest = new PasswordValidator("^[a-z]+$", customErrorMsg);
    
    // Test with an invalid password
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("PASSWORD123"));
    
    // Verify the error message is correctly handled
    assertEquals(customErrorMsg, exception.getMessage());
    
    // Verify exception details are available
    assertTrue(exception.getErrors().containsKey("password"));
  }
}