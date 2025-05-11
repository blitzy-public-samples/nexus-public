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
package org.sonatype.nexus.common.time;

import java.text.ParseException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.sonatype.nexus.common.time.DateHelper.toDateTime;
import static org.sonatype.nexus.common.time.DateHelper.toJavaDuration;
import static org.sonatype.nexus.common.time.DateHelper.toJodaDuration;
import static org.sonatype.nexus.common.time.DateHelper.toLocalDate;
import static org.sonatype.nexus.common.time.DateHelper.toOffsetDateTime;

/**
 * Tests for {@link DateHelper} utility class that handles conversions between different date/time representations.
 * Validates compatibility with Java 21 time APIs.
 */
public class DateHelperTest
{
  /**
   * Verifies conversion from Java OffsetDateTime to Joda DateTime.
   */
  @Test
  public void convertOffsetDateTimeToDateTime() {
    OffsetDateTime offsetDateTime = OffsetDateTime.parse("2010-06-30T01:20+00:00");
    DateTime jodaDateTime = new DateTime("2010-06-30T01:20+00:00");
    assertThat(toDateTime(offsetDateTime).toInstant(), equalTo(jodaDateTime.toInstant()));
  }

  /**
   * Verifies conversion from Joda DateTime to Java OffsetDateTime.
   */
  @Test
  public void convertDateTimeToOffsetDateTime() {
    OffsetDateTime offsetDateTime = OffsetDateTime.parse("2010-06-30T01:20+00:00");
    DateTime jodaDateTime = new DateTime("2010-06-30T01:20+00:00");
    assertThat(toOffsetDateTime(jodaDateTime).toInstant(), equalTo(offsetDateTime.toInstant()));
  }

  /**
   * Verifies conversion from Joda Duration to Java Duration.
   */
  @Test
  public void convertJodaDurationToJavaDuration() {
    Duration javaDuration = Duration.ofHours(5);
    org.joda.time.Duration jodaDuration = org.joda.time.Duration.standardHours(5);
    assertThat(toJavaDuration(jodaDuration), equalTo(javaDuration));
  }

  /**
   * Verifies conversion from Java Duration to Joda Duration.
   */
  @Test
  public void convertJavaDurationToJodaDuration() {
    Duration javaDuration = Duration.ofHours(5);
    org.joda.time.Duration jodaDuration = org.joda.time.Duration.standardHours(5);
    assertThat(toJodaDuration(javaDuration), equalTo(jodaDuration));
  }

  /**
   * Verifies conversion from Java Date to Java LocalDate.
   */
  @Test
  public void convertDateToLocalDate() throws ParseException {
    LocalDate javaLocalDate = LocalDate.of(2022, 6, 21);

    Date javaDate = Date.from(javaLocalDate.atStartOfDay(ZoneId.systemDefault()).toInstant());

    assertThat(toLocalDate(javaDate), equalTo(javaLocalDate));
  }
  
  /**
   * Verifies conversion from Java Date to Java LocalDate with date at system default timezone boundary.
   * This test ensures compatibility with Java 21's handling of timezone boundaries.
   */
  @Test
  public void convertDateToLocalDateAtTimezoneBoundary() throws ParseException {
    // Create a date at midnight in system default timezone
    LocalDate javaLocalDate = LocalDate.now();
    Date javaDate = Date.from(javaLocalDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    
    assertThat(toLocalDate(javaDate), equalTo(javaLocalDate));
  }
  
  /**
   * Verifies conversion between Java Duration and Joda Duration with large values.
   * This test ensures compatibility with Java 21's handling of large duration values.
   */
  @Test
  public void convertLargeDurationValues() {
    // Test with a large duration (100 days)
    Duration javaDuration = Duration.ofDays(100);
    org.joda.time.Duration jodaDuration = org.joda.time.Duration.standardDays(100);
    
    assertThat(toJodaDuration(javaDuration), equalTo(jodaDuration));
    assertThat(toJavaDuration(jodaDuration), equalTo(javaDuration));
  }
}