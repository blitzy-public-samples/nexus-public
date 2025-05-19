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
package org.sonatype.nexus.common.sequence;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.Assert;
import org.junit.Test; // Using JUnit 4 for backward compatibility
import org.junit.experimental.categories.Category;

// JUnit Jupiter imports are commented out for now to avoid conflicts
// Will be used when fully migrating to JUnit Jupiter
// import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for various {@link NumberSequence} implementations.
 */
@Category(Java21TestGroup.class)
public class NumberSequenceTest
    extends TestSupport
{
  @Test
  public void constantSequenceGeneratesExpectedValues() {
    long startValue = 10;

    ConstantNumberSequence cs = new ConstantNumberSequence(startValue);

    for (int i = 0; i < 20; i++) {
      assertThat(cs.next(), is(startValue));
    }

    cs.reset();

    for (int i = 0; i < 20; i++) {
      assertThat(cs.next(), is(startValue));
    }
  }

  @Test
  public void linearSequenceGeneratesExpectedValues() {
    long startValue = 0;

    // step=1, multiplier=1, shift=0
    // f(x) = 1*x+0 = x; x starts at 1
    LinearNumberSequence ls = new LinearNumberSequence(startValue, 1, 1, 0);

    for (int i = 1; i < 20; i++) {
      assertThat(ls.next(), is((long) i));
    }

    ls.reset();

    // forth and back
    for (int i = 1; i < 20; i++) {
      assertThat(ls.next(), is((long) i));
    }
    for (int i = 18; i >= 1; i--) {
      assertThat(ls.prev(), is((long) i));
    }
  }

  @Test
  public void linearSequenceWithComplexParametersGeneratesExpectedValues() {
    long startValue = 0;

    // step=10, multiplier=2, shift=10
    // f(x) = 1*x+0 = x; x starts at 1
    LinearNumberSequence ls = new LinearNumberSequence(startValue, 10, 2, 10);

    long f = 0;

    for (int i = 1; i < 20; i++) {
      f = 2 * (i * 10) + 10;
      assertThat(ls.next(), is(f));
    }

    ls.reset();

    // forth and back
    for (int i = 1; i < 20; i++) {
      f = 2 * (i * 10) + 10;
      assertThat(ls.next(), is(f));
    }
    for (int i = 18; i >= 1; i--) {
      f = 2 * (i * 10) + 10;
      assertThat(ls.prev(), is(f));
    }
  }

  @Test
  public void fibonacciSequenceGeneratesExpectedValues() {
    int[] fibonacciNumbers = new int[]{1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233};

    FibonacciNumberSequence fs = new FibonacciNumberSequence();

    for (int f : fibonacciNumbers) {
      assertThat(fs.next(), is((long) f));
    }

    fs.reset();

    for (int f : fibonacciNumbers) {
      assertThat(fs.next(), is((long) f));
    }
  }

  @Test
  public void customizedFibonacciSequenceGeneratesExpectedValues() {
    int[] fibonacciNumbers = new int[]{10, 10, 20, 30, 50, 80, 130, 210, 340, 550, 890, 1440, 2330};

    FibonacciNumberSequence fs = new FibonacciNumberSequence(10);

    for (int f : fibonacciNumbers) {
      assertThat(fs.next(), is((long) f));
    }

    fs.reset();

    for (int f : fibonacciNumbers) {
      assertThat(fs.next(), is((long) f));
    }
  }

  private static void ArrayUtils_reverse(int[] array) {
    if (array == null) {
      return;
    }
    int i = 0;
    int j = array.length - 1;
    int tmp;
    while (j > i) {
      tmp = array[j];
      array[j] = array[i];
      array[i] = tmp;
      j--;
      i++;
    }
  }

  @Test
  public void fibonacciSequenceSupportsBackAndForthNavigation() {
    int[] fibonacciNumbers = new int[]{10, 10, 20, 30, 50, 80, 130, 210, 340, 550, 890, 1440, 2330};

    FibonacciNumberSequence fs = new FibonacciNumberSequence(10);

    for (int f : fibonacciNumbers) {
      assertThat(fs.next(), is((long) f));
    }

    fs.reset();

    for (int f : fibonacciNumbers) {
      assertThat(fs.next(), is((long) f));
    }

    ArrayUtils_reverse(fibonacciNumbers);

    for (int f : fibonacciNumbers) {
      assertThat(fs.prev(), is((long) f));
    }
  }

  @Test
  public void linearSequenceWithLimiterRespectsLowerBound() {
    long startValue = 0;

    // step=1, multiplier=1, shift=0
    // f(x) = 1*x+0 = x; x starts at 1
    LinearNumberSequence ls = new LinearNumberSequence(startValue, 1, 1, 0);
    LowerLimitNumberSequence seq = new LowerLimitNumberSequence(ls, 1);

    // go prev 5 times, it should actually result in ONE state change
    for (int i = 1; i < 5; i++) {
      assertThat(seq.prev(), is(1L));
    }

    assertThat(seq.peek(), is(1L));

    assertThat(seq.next(), is(1L));
    assertThat(seq.next(), is(2L));

    seq.reset();

    assertThat(seq.next(), is(1L));
    assertThat(seq.next(), is(2L));
  }

}