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
package org.sonatype.nexus.common.property;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PropertiesFile}
 */
public class PropertiesFileTest
    extends TestSupport
{
  private File file;

  private PropertiesFile underTest;

  @BeforeEach
  public void setUp() {
    file = util.createTempFile();
    log("File: {}", file);
    underTest = new PropertiesFile(file);
  }

  @Test
  public void testStoreProperties() throws IOException {
    underTest.setProperty("foo", "bar");
    underTest.store();
    assertTrue(file.exists(), "File should exist after storing properties");
    assertThat(Files.readString(file.toPath()).length(), not(is(0)));

    // expect the first "comment" line of the file to contain a Date
    String firstLine = Files.readAllLines(file.toPath()).get(0);
    // Using java.time instead of Joda Time
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSSZ");
    LocalDateTime dateTime = LocalDateTime.parse(firstLine.substring(1), formatter);
    assertTrue(dateTime.isBefore(LocalDateTime.now()), "Date in properties file should be before now");

    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(file.toPath())) {
      props.load(in);
    }

    assertThat(props.keySet(), hasSize(1));
    assertThat(props.getProperty("foo"), is("bar"));
  }

  @Test
  public void testStoreProperties_withExplicitComment() throws IOException {
    String comment = "At the hundredth meridian where the great plains begin";
    underTest.store(comment);
    assertTrue(file.exists(), "File should exist after storing properties");
    assertThat(Files.readString(file.toPath()).length(), not(is(0)));

    // expect the first "comment" line of the file to contain the specified message
    String firstLine = Files.readAllLines(file.toPath()).get(0);
    assertThat(firstLine, is("#" + comment));
  }

  @Test
  public void testLoadProperties() throws IOException {
    Properties props = new Properties();
    props.setProperty("foo", "bar");

    try (OutputStream out = Files.newOutputStream(file.toPath())) {
      props.store(out, null);
    }

    underTest.load();
    assertThat(props.keySet(), hasSize(1));
    assertThat(props.getProperty("foo"), is("bar"));
  }

  @Test
  public void testConcurrentAccessWithVirtualThreads() throws Exception {
    // Set up initial properties
    underTest.setProperty("initial", "value");
    underTest.store();
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Each thread reads and writes a unique property
            PropertiesFile threadLocalProps = new PropertiesFile(file);
            threadLocalProps.load();
            threadLocalProps.setProperty("key" + index, "value" + index);
            threadLocalProps.store();
          } catch (Exception e) {
            log.error("Error in virtual thread task", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
      assertThat("No errors should occur during concurrent access", errorCount.get(), is(0));
      
      // Verify final state
      PropertiesFile result = new PropertiesFile(file);
      result.load();
      
      // Should have initial property + taskCount new properties
      assertThat(result.getProperties().size(), is(taskCount + 1));
      assertThat(result.getProperty("initial"), is("value"));
      
      // Verify all properties were written correctly
      for (int i = 0; i < taskCount; i++) {
        assertThat(result.getProperty("key" + i), is("value" + i));
      }
    }
  }
}