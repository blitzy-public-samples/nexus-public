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

// Maintaining JUnit 4 compatibility while preparing for migration to JUnit 5
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Tests {@link RandomExponentialSequence}
 */
@Category(Java21TestGroup.class)
public class RandomExponentialSequenceTest
{
  @Test
  public void sequenceGeneratesExpectedValuesWithoutRandomness() {
    RandomExponentialSequence seq = RandomExponentialSequence.builder()
        .start(1)
        .factor(2)
        .maxDeviation(0.0f)
        .build();

    assertThat(seq.next(), is(1L));
    assertThat(seq.next(), is(2L));
    assertThat(seq.next(), is(4L));
    assertThat(seq.next(), is(8L));
    assertThat(seq.next(), is(16L));
  }

  @Test
  public void sequenceGeneratesValuesWithinBoundsWithFullRandomness() {
    for (int i = 0; i < 1000; i++) {
      RandomExponentialSequence seq = RandomExponentialSequence.builder()
          .start(10)
          .factor(2)
          .maxDeviation(2.0f)
          .build();

      assertThat(seq.next(), is(10L));
      long n = seq.next();
      assertThat(n, greaterThanOrEqualTo(10L));
      assertThat(n, lessThanOrEqualTo(40L));
    }
  }
}
