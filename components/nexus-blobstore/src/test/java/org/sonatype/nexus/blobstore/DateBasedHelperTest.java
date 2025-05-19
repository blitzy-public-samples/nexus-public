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
package org.sonatype.nexus.blobstore;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.MatcherAssert.assertThat;
import static java.lang.StringTemplate.STR;

public class DateBasedHelperTest
{
  // Reference time for testing date-based path generation
  public static final OffsetDateTime NOW = OffsetDateTime.parse("2024-10-25T14:30:30Z");

  @Test
  public void testGeneratePrefixesMinutesLess30() {
    Duration duration = Duration.ofMinutes(1);
    var prefixes = DateBasedHelper.generatePrefixes(NOW.minus(duration), NOW);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25/14/29", STR."2024/10/25/14/30"));

    duration = Duration.ofMinutes(5);
    prefixes = DateBasedHelper.generatePrefixes(NOW.minus(duration), NOW);
    assertThat(prefixes,
        containsInAnyOrder(STR."2024/10/25/14/30", STR."2024/10/25/14/29", STR."2024/10/25/14/28", STR."2024/10/25/14/27",
            STR."2024/10/25/14/26", STR."2024/10/25/14/25"));
  }

  @Test
  public void testGeneratePrefixesMinutesOver30() {
    Duration duration = Duration.ofMinutes(31);
    var prefixes = DateBasedHelper.generatePrefixes(NOW.minus(duration), NOW);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25/14"));
  }

  @Test
  public void testGeneratePrefixesHoursLess24() {
    Duration duration = Duration.ofHours(3);
    var prefixes = DateBasedHelper.generatePrefixes(NOW.minus(duration), NOW);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25/14", STR."2024/10/25/13", STR."2024/10/25/11", STR."2024/10/25/12"));
  }

  @Test
  public void testGeneratePrefixesHoursOver24() {
    Duration duration = Duration.ofHours(25);
    var prefixes = DateBasedHelper.generatePrefixes(NOW.minus(duration), NOW);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25", STR."2024/10/24"));
  }

  @Test
  public void testGeneratePrefixesHoursOne() {
    Duration duration = Duration.ofHours(1);
    // sometimes current hour were not generated as prefix, so define specific value
    var currentTime = OffsetDateTime.parse("2024-10-25T14:32:30Z");
    var prefixes = DateBasedHelper.generatePrefixes(currentTime.minus(duration), currentTime);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25/14", STR."2024/10/25/13"));
  }

  @Test
  public void testGeneratePrefixesMinutesOne() {
    Duration duration = Duration.ofMinutes(1);
    // sometimes current hour were not generated as prefix, so define specific value
    var currentTime = OffsetDateTime.parse("2024-10-25T14:32:30Z");
    var prefixes = DateBasedHelper.generatePrefixes(currentTime.minus(duration), currentTime);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25/14/32", STR."2024/10/25/14/31"));
  }

  @Test
  public void testGeneratePrefixesDaysOne() {
    Duration duration = Duration.ofDays(1);
    // sometimes current hour were not generated as prefix, so define specific value
    var currentTime = OffsetDateTime.parse("2024-10-25T14:32:30Z");
    var prefixes = DateBasedHelper.generatePrefixes(currentTime.minus(duration), currentTime);
    assertThat(prefixes, containsInAnyOrder(STR."2024/10/25", STR."2024/10/24"));
  }
}