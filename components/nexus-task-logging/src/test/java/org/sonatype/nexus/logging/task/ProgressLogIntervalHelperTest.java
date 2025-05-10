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
package org.sonatype.nexus.logging.task;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.test.util.Whitebox;

import com.google.common.base.Stopwatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

@ExtendWith(MockitoExtension.class)
@Java21TestGroup
public class ProgressLogIntervalHelperTest
{
  @Mock
  private Logger logger;

  /**
   * Tests that logging only occurs after the configured interval has elapsed.
   */
  @Test
  void intervalElapsed() {
    // Mock the Stopwatch for deterministic time testing
    Stopwatch mockProgress = mock(Stopwatch.class);
    
    String arg = "arg";
    Object[] argArray = {arg};

    ProgressLogIntervalHelper underTest = new ProgressLogIntervalHelper(logger, 1);
    
    // Set the mocked progress stopwatch
    Whitebox.setInternalState(underTest, "progress", mockProgress);
    
    // First call - return 0 seconds elapsed (less than interval)
    when(mockProgress.elapsed(TimeUnit.SECONDS)).thenReturn(0L);
    when(mockProgress.reset()).thenReturn(mockProgress);
    when(mockProgress.start()).thenReturn(mockProgress);

    // on immediate call interval will not have elapsed so the logger should not be hit
    underTest.info("Test 1", arg);
    verify(logger, never()).info(PROGRESS, "Test 1", argArray);

    // Second call - return 2 seconds elapsed (more than interval)
    when(mockProgress.elapsed(TimeUnit.SECONDS)).thenReturn(2L);

    // invoke after interval elapsed and now logger should have been hit
    underTest.info("Test 2", arg);
    verify(logger).info(PROGRESS, "Test 2", argArray);
  }

  /**
   * Tests the formatting of elapsed time in various durations.
   *
   * @param seconds the duration in seconds
   * @param expected the expected formatted string
   */
  @ParameterizedTest
  @MethodSource("elapsedTimeParameters")
  void getElapsedTest(long seconds, String expected) {
    Stopwatch elapsedStopwatch = mock(Stopwatch.class);
    when(elapsedStopwatch.elapsed()).thenReturn(Duration.ofSeconds(seconds));

    ProgressLogIntervalHelper progressLogger = new ProgressLogIntervalHelper(logger, 1);
    Whitebox.setInternalState(progressLogger, "elapsed", elapsedStopwatch);
    assertEquals(expected, progressLogger.getElapsed());
  }
  
  /**
   * Provides test parameters for the getElapsedTest method.
   *
   * @return a stream of arguments containing seconds and expected formatted time
   */
  static Stream<Arguments> elapsedTimeParameters() {
    return Stream.of(
        Arguments.of(0L, "0s"),
        Arguments.of(1L, "1s"),
        Arguments.of(60L, "1m 0s"),
        Arguments.of(61L, "1m 1s"),
        Arguments.of(3599L, "59m 59s"),
        Arguments.of(3600L, "1h 0m 0s"),
        Arguments.of(3601L, "1h 0m 1s"),
        Arguments.of(86400L, "1d 0h 0m 0s"),
        Arguments.of(1296000L, "15d 0h 0m 0s"),
        Arguments.of(2161045L, "25d 0h 17m 25s")
    );
  }
}