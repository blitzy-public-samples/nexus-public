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
package org.sonatype.nexus.cleanup.storage.config;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.storage.config.RegexCriteriaValidator.InvalidExpressionException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RegexCriteriaValidatorTest
    extends TestSupport
{
  private static final String EXPRESSION = "org/sonatype";
  private static final String INVALID_EXPRESSION = "hello(world";

  @Test
  public void invalidExpressionShouldThrowException() {
    assertThrows(InvalidExpressionException.class, () -> {
      RegexCriteriaValidator.validate(INVALID_EXPRESSION);
    });
  }

  @Test
  public void validExpressionShouldReturnSameExpression() {
    assertThat(RegexCriteriaValidator.validate(EXPRESSION), is(EXPRESSION));
  }
  
  @Test
  public void invalidExpressionShouldContainDetailedErrorMessage() {
    // Using Java 21 String Templates to format the expected error message pattern
    String expectedErrorPattern = STR."Invalid regular expression pattern: \"(\" expected";
    
    InvalidExpressionException exception = assertThrows(InvalidExpressionException.class, 
        () -> RegexCriteriaValidator.validate(INVALID_EXPRESSION));
    
    assertThat(exception.getMessage(), containsString(expectedErrorPattern));
  }
}