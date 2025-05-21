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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @since 3.25
 */
public class PasswordValidatorTest
{
  @Test
  public void testValidate_noValidation() {
    PasswordValidator underTest = new PasswordValidator(null, null);

    assertDoesNotThrow(() -> underTest.validate("foo"));
  }

  @Test
  public void testValidate_passValidation() {
    PasswordValidator underTest = new PasswordValidator(".*", null);

    assertDoesNotThrow(() -> underTest.validate("foo"));
  }

  @Test
  public void testValidate_failValidation() {
    PasswordValidator underTest = new PasswordValidator("[a]+", null);

    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("foo"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }

  @Test
  public void testValidate_failValidationCustomErrorMessage() {
    PasswordValidator underTest = new PasswordValidator("[a]+", "Bad bad bad");

    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, 
        () -> underTest.validate("foo"));
    assertEquals("Bad bad bad", exception.getMessage());
  }
  
  @Test
  public void testValidate_withJava21PatternMatching() {
    // Test with a pattern that would be used in pattern matching
    PasswordValidator underTest = new PasswordValidator("^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\S+$).{8,}$", null);
    
    // Should fail - doesn't meet complex password requirements
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class,
        () -> underTest.validate("simplepassword"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
    
    // Should pass - meets complex password requirements
    assertDoesNotThrow(() -> underTest.validate("Complex@Pass1"));
  }
  
  @Test
  public void testValidate_withStringTemplates() {
    // Test with passwords containing String Template syntax (new in Java 21)
    PasswordValidator underTest = new PasswordValidator(".*\\{.*\\}.*", null);
    
    // Should pass - contains string template-like syntax
    assertDoesNotThrow(() -> underTest.validate("pass{word}123"));
    
    // Should fail - doesn't contain string template-like syntax
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class,
        () -> underTest.validate("password123"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }
  
  @Test
  public void testValidate_withEscapedCharactersInPattern() {
    // Test with patterns containing special regex characters that need escaping
    PasswordValidator underTest = new PasswordValidator(".*\\$\\{.*\\}.*", null);
    
    // Should pass - contains ${...} syntax
    assertDoesNotThrow(() -> underTest.validate("pass${word}123"));
    
    // Should fail - doesn't contain ${...} syntax
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class,
        () -> underTest.validate("pass{word}123"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }
  
  @Test
  public void testValidate_withJava21PatternMatchingCompatibility() {
    // Test with a pattern that uses character classes compatible with Java 21 pattern matching
    PasswordValidator underTest = new PasswordValidator("\\w+\\d+\\w+", null);
    
    // Should pass - contains word characters followed by digits followed by word characters
    assertDoesNotThrow(() -> underTest.validate("abc123def"));
    
    // Should fail - doesn't match the pattern
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class,
        () -> underTest.validate("123abcdef"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }
  
  @Test
  public void testValidate_withStringTemplatesCompatibility() {
    // Test with passwords containing String Template syntax (new in Java 21)
    // The pattern requires a password with String Template-like syntax
    PasswordValidator underTest = new PasswordValidator(".*\\\\\\{.*\\}.*", null);
    
    // Should pass - contains escaped String Template syntax (\{...})
    assertDoesNotThrow(() -> underTest.validate("pass\\{template}123"));
    
    // Should fail - doesn't contain escaped String Template syntax
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class,
        () -> underTest.validate("password123"));
    assertEquals("Password does not match corporate policy", exception.getMessage());
  }
  
  @Test
  public void testValidate_withComplexPatternForJava21() {
    // Test with a more complex pattern that uses features compatible with Java 21
    // This pattern requires at least one lowercase letter, one uppercase letter, one digit,
    // one special character, and a minimum length of 8 characters
    PasswordValidator underTest = new PasswordValidator(
        "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%^&+=])(?=\\S+$).{8,}$", 
        "Password must contain at least one lowercase letter, one uppercase letter, one digit, one special character, and be at least 8 characters long");
    
    // Should pass - meets all requirements
    assertDoesNotThrow(() -> underTest.validate("Abcd1234@"));
    
    // Should fail - missing special character
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class,
        () -> underTest.validate("Abcd1234"));
    assertEquals("Password must contain at least one lowercase letter, one uppercase letter, one digit, one special character, and be at least 8 characters long", 
        exception.getMessage());
  }
