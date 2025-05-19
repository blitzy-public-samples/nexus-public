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
package org.sonatype.nexus.common.text;

import org.sonatype.goodies.testsupport.TestSupport;

// Keep JUnit 4 import for backward compatibility with JUnit Vintage Engine
import org.junit.Test;

// Import JUnit Jupiter assertions for enhanced assertion capabilities
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link Strings2}
 */
public class Strings2Test
    extends TestSupport
{
  @Test
  public void isBlankShouldIdentifyBlankStrings() {
    assertTrue(Strings2.isBlank(null), "null should be considered blank");
    assertTrue(Strings2.isBlank(""), "empty string should be considered blank");
    assertTrue(Strings2.isBlank("   "), "whitespace string should be considered blank");
    assertFalse(Strings2.isBlank("foo"), "non-blank string should not be considered blank");
  }

  @Test
  public void isEmptyShouldIdentifyEmptyStrings() {
    assertTrue(Strings2.isEmpty(null), "null should be considered empty");
    assertTrue(Strings2.isEmpty(""), "empty string should be considered empty");
    assertFalse(Strings2.isEmpty("   "), "whitespace string should not be considered empty");
    assertFalse(Strings2.isEmpty("foo"), "non-empty string should not be considered empty");
  }

  @Test
  public void maskShouldReturnNullForNullInput() {
    assertThat(Strings2.mask(null), nullValue());
  }

  @Test
  public void maskShouldReturnMaskForNonNullInput() {
    // any non-null returns MASK
    assertThat(Strings2.mask("a"), is(Strings2.MASK));
    assertThat(Strings2.mask("ab"), is(Strings2.MASK));
    assertThat(Strings2.mask("abc"), is(Strings2.MASK));
    assertThat(Strings2.mask("abcd"), is(Strings2.MASK));
    assertThat(Strings2.mask("abcde"), is(Strings2.MASK));
  }

  @Test
  public void encodeSeparatorShouldFormatNodeLikeStrings() {
    String input = "05F4743FA756584643FDF9D0577BE4FB079289C6";
    String output = Strings2.encodeSeparator(input, '-', 8);
    assertThat(output, equalTo("05F4743F-A7565846-43FDF9D0-577BE4FB-079289C6"));
  }

  @Test
  public void encodeSeparatorShouldFormatFingerprintLikeStrings() {
    String input = "05F4743FA756584643FDF9D0577BE4FB079289C6";
    String output = Strings2.encodeSeparator(input, ':', 2);
    assertThat(output, equalTo("05:F4:74:3F:A7:56:58:46:43:FD:F9:D0:57:7B:E4:FB:07:92:89:C6"));
  }
}