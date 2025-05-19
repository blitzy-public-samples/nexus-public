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
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.sonatype.nexus.common.time.DateHelper.toDateTime;
import static org.sonatype.nexus.common.time.DateHelper.toJavaDuration;
import static org.sonatype.nexus.common.time.DateHelper.toJodaDuration;
import static org.sonatype.nexus.common.time.DateHelper.toLocalDate;
import static org.sonatype.nexus.common.time.DateHelper.toOffsetDateTime;

/**
 * Tests for {@link DateHelper} class to ensure proper date and time conversion
 * between Java time API and Joda time API.
 * 
 * Java 21 introduces improved time handling with more consistent behavior across
 * different time zones and better performance for time-related operations.
 */
@Category(Java21TestGroup.class)
public class DateHelperTest
{
  /**
   * Tests conversion from OffsetDateTime to Joda DateTime.
   * Java 21 maintains consistent behavior with previous versions for this conversion.
   */
  @Test
  public void shouldConvertOffsetDateTimeToJodaDateTime() {
    OffsetDateTime offsetDateTime = OffsetDateTime.parse("2010-06-30T01:20+00:00");
    DateTime jodaDateTime = new DateTime("2010-06-30T01:20+00:00");
    assertThat(toDateTime(offsetDateTime).toInstant(), is(equalTo(jodaDateTime.toInstant())));
  }

  /**
   * Tests conversion from Joda DateTime to OffsetDateTime.
   * Java 21 ensures accurate timezone offset preservation during conversion.
   */
  @Test
  public void shouldConvertJodaDateTimeToOffsetDateTime() {
    OffsetDateTime offsetDateTime = OffsetDateTime.parse("2010-06-30T01:20+00:00");
    DateTime jodaDateTime = new DateTime("2010-06-30T01:20+00:00");
    assertThat(toOffsetDateTime(jodaDateTime).toInstant(), is(equalTo(offsetDateTime.toInstant())));
  }

  /**
   * Tests conversion from Joda Duration to Java Duration.
   * Java 21 maintains consistent duration conversion with nanosecond precision.
   */
  @Test
  public void shouldConvertJodaDurationToJavaDuration() {
    Duration javaDuration = Duration.ofHours(5);
    org.joda.time.Duration jodaDuration = org.joda.time.Duration.standardHours(5);
    assertThat(toJavaDuration(jodaDuration), is(equalTo(javaDuration)));
  }

  /**
   * Tests conversion from Java Duration to Joda Duration.
   * Java 21 ensures accurate duration conversion between APIs.
   */
  @Test
  public void shouldConvertJavaDurationToJodaDuration() {
    Duration javaDuration = Duration.ofHours(5);
    org.joda.time.Duration jodaDuration = org.joda.time.Duration.standardHours(5);
    assertThat(toJodaDuration(javaDuration), is(equalTo(jodaDuration)));
  }

  /**
   * Tests conversion from java.util.Date to LocalDate.
   * Java 21 maintains consistent behavior for date conversions with system default timezone.
   */
  @Test
  public void shouldConvertJavaDateToLocalDate() throws ParseException {
    LocalDate javaLocalDate = LocalDate.of(2022, 6, 21);

    Date javaDate = Date.from(javaLocalDate.atStartOfDay(ZoneId.systemDefault()).toInstant());

    assertThat(toLocalDate(javaDate), is(equalTo(javaLocalDate)));
  }
}