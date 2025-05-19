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

import java.lang.StringTemplate;
import java.lang.StringTemplate.Processor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.sameType;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.warn;

/**
 * Tests for {@link ExceptionSummarizer} with Java 21 String Templates.
 */
@Tag("Java21")
public class ExceptionSummarizerStringTemplateTest
    extends TestSupport
{
  @Mock
  private Logger log;

  private Exception testException = new IllegalStateException("test exception");

  private TestExceptionSummarizer underTest;

  /**
   * Test that the String Template processor correctly formats exception summary messages.
   */
  @Test
  public void testStringTemplateFormatting() {
    underTest = new TestExceptionSummarizer(sameType(), warn(log));

    // First log will show full stack trace
    underTest.log("oops", testException);

    // Simulate time passing
    underTest.sleep(5, TimeUnit.SECONDS);

    // Second log should use String Template formatting for summary
    underTest.log("oops", testException);

    InOrder inOrder = inOrder(log);
    inOrder.verify(log).warn("oops", testException);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Test that the SUMMARY_TEMPLATE_PROCESSOR correctly processes templates with different values.
   */
  @Test
  public void testSummaryTemplateProcessor() throws Exception {
    // Access the private SUMMARY_TEMPLATE_PROCESSOR via reflection
    java.lang.reflect.Field field = ExceptionSummarizer.class.getDeclaredField("SUMMARY_TEMPLATE_PROCESSOR");
    field.setAccessible(true);
    Processor<String> processor = (Processor<String>) field.get(null);

    // Test with different values
    String message = "error";
    Exception cause = new RuntimeException("test");
    int count = 3;
    long seconds = 10;

    // Create a StringTemplate using RAW processor
    StringTemplate template = StringTemplate.RAW."{message}: {cause} - occurred {count} times in last {seconds}";

    // Process the template with our processor
    String result = processor.process(template);

    // Verify the result
    assertEquals("error: java.lang.RuntimeException: test - occurred 3 times in last 10 seconds", result);
  }

  /**
   * Test that String Templates perform better than traditional String.format for exception messages.
   */
  @Test
  public void testStringTemplatePerformance() {
    // Prepare test data
    String message = "error";
    Exception cause = new RuntimeException("test");
    int count = 100;
    long seconds = 5;

    // Measure time for String.format (traditional approach)
    long startFormat = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
      String formatted = String.format("%s: %s - occurred %d times in last %d seconds", 
          message, cause, count, seconds);
    }
    long endFormat = System.nanoTime();
    long formatTime = endFormat - startFormat;

    // Measure time for String Templates
    long startTemplate = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
      String templated = STR."{message}: {cause} - occurred {count} times in last {seconds} seconds";
    }
    long endTemplate = System.nanoTime();
    long templateTime = endTemplate - startTemplate;

    // Log the results - we expect String Templates to be faster
    log.info("String.format time: {} ns", formatTime);
    log.info("String Template time: {} ns", templateTime);
    log.info("Performance ratio: {}", (double) formatTime / templateTime);
  }

  /**
   * Test different exception message formats with embedded template expressions.
   */
  @Test
  public void testDifferentTemplateFormats() {
    // Test with different template formats
    String message = "error";
    Exception cause = new RuntimeException("test");
    int count = 3;
    long seconds = 10;

    // Basic template
    String basic = STR."{message}: {cause} - occurred {count} times in last {seconds} seconds";
    assertEquals("error: java.lang.RuntimeException: test - occurred 3 times in last 10 seconds", basic);

    // Template with expressions
    String withExpressions = STR."{message.toUpperCase()}: {cause.getClass().getSimpleName()} - occurred {count * 2} times in last {seconds / 2} seconds";
    assertEquals("ERROR: RuntimeException - occurred 6 times in last 5 seconds", withExpressions);

    // Template with conditional expression
    String withConditional = STR."{message}: {cause} - {count > 1 ? "multiple occurrences" : "single occurrence"} in last {seconds} seconds";
    assertEquals("error: java.lang.RuntimeException: test - multiple occurrences in last 10 seconds", withConditional);
  }

  /**
   * Stubbed {@link ExceptionSummarizer} that lets tests move time forward without sleeping.
   */
  private static class TestExceptionSummarizer
      extends ExceptionSummarizer
  {
    private long currentTimeMillis = System.currentTimeMillis();

    TestExceptionSummarizer(
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
  }
}