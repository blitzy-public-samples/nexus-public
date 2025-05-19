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
import org.sonatype.nexus.virtualthread.Java21TestGroup;

// JUnit Jupiter imports for JUnit 5
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
// JUnit 4 compatibility imports
import org.junit.Test;
import org.junit.experimental.categories.Category;
// Mockito imports
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import static org.mockito.Mockito.inOrder;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.sameText;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.sameType;
import static org.sonatype.nexus.common.log.ExceptionSummarizer.warn;

/**
 * Tests for {@link ExceptionSummarizer}.
 * 
 * These tests validate the exception summarization functionality which is critical for log management
 * in Java 21 environments where improved exception handling and virtual threads can generate
 * different exception patterns.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class ExceptionSummarizerTest
    extends TestSupport
{
  @Mock
  private Logger log;

  private Exception firstCause = new IllegalArgumentException("first");

  private Exception secondCause = new IllegalStateException("second");

  private Exception thirdCause = new IllegalStateException("third");

  private Exception repeatCause = new IllegalStateException();

  private TestExceptionSummarizer underTest;

  /**
   * Tests exception summarization by type, which is particularly important in Java 21
   * where virtual threads may generate more exceptions during high concurrency operations.
   * This ensures log files remain manageable even with increased thread counts.
   */
  @Test
  public void summarizeExceptionsByTypeShouldGroupSimilarExceptions() throws Exception {
    underTest = new TestExceptionSummarizer(sameType(), warn(log));

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

    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.sleep(60, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- full stack, because over 1min since last summary

    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- summary (1 repeat)

    InOrder inOrder = inOrder(log);
    inOrder.verify(log).warn("oops", firstCause);
    inOrder.verify(log).warn("oops", secondCause);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 3 times in last 5 seconds", (Exception) null);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 5 times in last 10 seconds", (Exception) null);
    inOrder.verify(log).warn("oops", repeatCause);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Tests exception summarization by message text, which helps manage logs when
   * Java 21 virtual threads produce exceptions with identical messages but from different
   * thread contexts. This is especially relevant for I/O operations that may use virtual threads.
   */
  @Test
  public void summarizeExceptionsByTextShouldGroupExceptionsWithSameMessage() throws Exception {
    underTest = new TestExceptionSummarizer(sameText(), warn(log));

    underTest.log("oops", firstCause); // <-- full stack

    underTest.log("oops", secondCause); // <-- full stack, because type changed

    underTest.log("oops", thirdCause); // <-- full stack, because text changed

    underTest.log("oops", repeatCause); // <-- full stack, because text changed

    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- summary (1 repeat)

    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.sleep(10, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- summary (5 repeats)

    underTest.log("oops", repeatCause);
    underTest.log("oops", repeatCause);
    underTest.sleep(60, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- full stack, because over 1min since last summary

    underTest.sleep(5, TimeUnit.SECONDS);
    underTest.log("oops", repeatCause); // <-- summary (1 repeat)

    InOrder inOrder = inOrder(log);
    inOrder.verify(log).warn("oops", firstCause);
    inOrder.verify(log).warn("oops", secondCause);
    inOrder.verify(log).warn("oops", thirdCause);
    inOrder.verify(log).warn("oops", repeatCause);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 5 times in last 10 seconds", (Exception) null);
    inOrder.verify(log).warn("oops", repeatCause);
    inOrder.verify(log)
        .warn("oops: java.lang.IllegalStateException - occurred 1 times in last 5 seconds", (Exception) null);
    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Stubbed {@link ExceptionSummarizer} that lets tests move time forward without sleeping.
   * This approach is compatible with Java 21's improved time handling and virtual thread scheduling.
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