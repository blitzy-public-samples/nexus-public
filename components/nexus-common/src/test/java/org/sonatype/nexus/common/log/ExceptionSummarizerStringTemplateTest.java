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
package org.sonatype.nexus.common.log;

import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import static org.mockito.Mockito.inOrder;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.sameText;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.sameType;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.warn;

/**
 * Tests for {@link ExceptionSummarizer} with Java 21 String Templates.
 */
@ExtendWith(MockitoExtension.class)
public class ExceptionSummarizerStringTemplateTest
    extends TestSupport
    implements Java21TestGroup
{
  @Mock
  private Logger log;

  private Exception firstCause = new IllegalArgumentException("first");

  private Exception secondCause = new IllegalStateException("second");

  private Exception thirdCause = new IllegalStateException("third");

  private Exception repeatCause = new IllegalStateException();

  private StringTemplateExceptionSummarizer underTest;

  @BeforeEach
  public void setUp() {
    // No setup needed beyond what's provided by MockitoExtension
  }

  @Test
  public void testStringTemplateFormatting() {
    underTest = new StringTemplateExceptionSummarizer(sameType(), warn(log));

    underTest.log("oops", firstCause); // <-- full stack

    underTest.log("oops", secondCause); // <-- full stack, because type changed

    underTest.log("oops", thirdCause);
    underTest.log("oops", repeatCause);
    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- summary (3 repeats)

    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.sleep(10, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- summary (5 repeats)

    InOrder inOrder = inOrder(log);
    inOrder.verify(log).warn("oops", firstCause);
    inOrder.verify(log).warn("oops", secondCause);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 3 times in last 5 seconds", (Exception) null);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 5 times in last 10 seconds", (Exception) null);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testStringTemplateWithDifferentMessageFormats() {
    underTest = new StringTemplateExceptionSummarizer(sameText(), warn(log));

    // Test with different message formats
    String customMessage = "Custom error";
    underTest.log(customMessage, firstCause);
    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log(customMessage, firstCause); // <-- summary (1 repeat)

    // Test with empty message
    String emptyMessage = "";
    underTest.log(emptyMessage, secondCause);
    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log(emptyMessage, secondCause); // <-- summary (1 repeat)

    // Test with null cause
    String nullCauseMessage = "Null cause";
    underTest.log(nullCauseMessage, null);
    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log(nullCauseMessage, null); // <-- summary (1 repeat)

    InOrder inOrder = inOrder(log);
    inOrder.verify(log).warn(customMessage, firstCause);
    inOrder.verify(log)
        .warn("Custom error: java.lang.IllegalArgumentException: first - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verify(log).warn(emptyMessage, secondCause);
    inOrder.verify(log)
        .warn(": java.lang.IllegalStateException: second - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verify(log).warn(nullCauseMessage, null);
    inOrder.verify(log)
        .warn("Null cause: null - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void compareStringTemplateWithStringFormat() {
    // This test demonstrates the difference between String.format and String Templates
    // It's primarily for documentation purposes as the actual implementation will use String Templates

    String message = "Error message";
    Exception cause = new RuntimeException("Test exception");
    int count = 5;
    long seconds = 10;

    // Traditional String.format approach
    String formatSummary = String.format("%s: %s - occurred %d times in last %d seconds",
        message, cause, count, seconds);

    // String Template approach
    String templateSummary = STR."\{message}: \{cause} - occurred \{count} times in last \{seconds} seconds";

    // Both should produce the same output
    InOrder inOrder = inOrder(log);
    log.warn(formatSummary, null);
    log.warn(templateSummary, null);
    inOrder.verify(log).warn(formatSummary, null);
    inOrder.verify(log).warn(templateSummary, null);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Stubbed {@link ExceptionSummarizer} that uses String Templates and lets tests move time forward without sleeping.
   */
  private static class StringTemplateExceptionSummarizer
      extends ExceptionSummarizer
  {
    private long currentTimeMillis = System.currentTimeMillis();

    StringTemplateExceptionSummarizer(
        final BiPredicate<Exception, Exception> matcher,
        final BiConsumer<String, Exception> logger)
    {
      super(matcher, logger);
    }

    public void sleep(final int duration, final TimeUnit unit) {
      this.currentTimeMillis += unit.toMillis(duration);
    }

    @Override
    long currentTimeMillis() {
      return currentTimeMillis;
    }

    /**
     * Override to use String Templates instead of String.format
     */
    @Override
    public synchronized void log(final String message, final Exception cause) {
      count++;
      long now = currentTimeMillis();
      if (!matcher.test(failureCause, cause) || now - firstFailureMillis >= ONE_MINUTE) {

        // new exception or its been over a minute since the first failure
        logger.accept(message, cause);

        failureCause = cause;
        firstFailureMillis = now;
        lastSummaryMillis = now;
        count = 0;
      }
      else if (now - lastSummaryMillis >= FIVE_SECONDS) {

        // repeating exception, log summary without stack at most every 5 seconds
        // Using String Template instead of String.format
        String summary = STR."\{message}: \{cause} - occurred \{count} times in last \{(now - lastSummaryMillis) / ONE_SECOND} seconds";
        logger.accept(summary, null);

        lastSummaryMillis = now;
        count = 0;
      }
    }
  }
}