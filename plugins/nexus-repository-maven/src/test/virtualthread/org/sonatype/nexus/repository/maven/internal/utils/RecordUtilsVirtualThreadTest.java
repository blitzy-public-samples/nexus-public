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
package org.sonatype.nexus.repository.maven.internal.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.collect.ImmutableMap;
import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.apache.maven.index.reader.Record.ARTIFACT_ID;
import static org.apache.maven.index.reader.Record.FILE_EXTENSION;
import static org.apache.maven.index.reader.Record.GROUP_ID;
import static org.apache.maven.index.reader.Record.Type.ARTIFACT_ADD;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.repository.maven.internal.utils.RecordUtils.gavceForRecord;

/**
 * Tests for {@link RecordUtils} when executed within Java 21 Virtual Threads.
 */
@DisplayName("RecordUtils Virtual Thread Tests")
public class RecordUtilsVirtualThreadTest
    extends TestSupport
{
  public static final String GROUP = "group";

  public static final String ARTIFACT = "artifact";

  public static final String VERSION = "version";

  public static final String CLASSIFIER = "classifier";

  public static final String EXTENSION = "extension";

  @Test
  @DisplayName("Should convert Record to GAVCE in a Virtual Thread")
  public void shouldConvertRecordToGavceInVirtualThread() throws Exception {
    // Create and start a virtual thread
    Future<String> result = Thread.ofVirtual().name("gavce-test").start(() -> {
      // Verify we're running in a virtual thread
      Thread currentThread = Thread.currentThread();
      assertTrue(currentThread.isVirtual(), "Test should be running in a virtual thread");
      log.info("Running in virtual thread: {}", currentThread);
      
      // Test the RecordUtils.gavceForRecord method
      Record record = new Record(ARTIFACT_ADD, ImmutableMap.of(GROUP_ID, GROUP,
          ARTIFACT_ID, ARTIFACT,
          Record.VERSION, VERSION,
          Record.CLASSIFIER, CLASSIFIER,
          FILE_EXTENSION, EXTENSION));

      return gavceForRecord(record);
    });

    // Get the result from the virtual thread
    String gavce = result.get();
    assertThat(gavce, is(equalTo(GROUP + ARTIFACT + VERSION + CLASSIFIER + ":" + EXTENSION)));
  }

  @Test
  @DisplayName("Should handle blank classifier when converting Record to GAVCE in a Virtual Thread")
  public void shouldHandleBlankClassifierWhenConvertRecordToGavceInVirtualThread() throws Exception {
    // Create and start a virtual thread
    Future<String> result = Thread.ofVirtual().name("gavce-blank-classifier-test").start(() -> {
      // Verify we're running in a virtual thread
      Thread currentThread = Thread.currentThread();
      assertTrue(currentThread.isVirtual(), "Test should be running in a virtual thread");
      log.info("Running in virtual thread: {}", currentThread);
      
      // Test the RecordUtils.gavceForRecord method with blank classifier
      String c = "";
      Record record = new Record(ARTIFACT_ADD, ImmutableMap.of(GROUP_ID, GROUP,
          ARTIFACT_ID, ARTIFACT,
          Record.VERSION, VERSION,
          Record.CLASSIFIER, c,
          FILE_EXTENSION, EXTENSION));

      return gavceForRecord(record);
    });

    // Get the result from the virtual thread
    String gavce = result.get();
    assertThat(gavce, is(equalTo(GROUP + ARTIFACT + VERSION + "n/a" + ":" + EXTENSION)));
  }

  @Test
  @DisplayName("Should handle concurrent GAVCE string construction in multiple Virtual Threads")
  public void shouldHandleConcurrentGavceStringConstructionInMultipleVirtualThreads() 
      throws InterruptedException, ExecutionException {
    // Number of concurrent threads to test with
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<Future<String>> results = new ArrayList<>(threadCount);
    
    // Create records with and without classifiers
    Record recordWithClassifier = new Record(ARTIFACT_ADD, ImmutableMap.of(GROUP_ID, GROUP,
        ARTIFACT_ID, ARTIFACT,
        Record.VERSION, VERSION,
        Record.CLASSIFIER, CLASSIFIER,
        FILE_EXTENSION, EXTENSION));
        
    Record recordWithoutClassifier = new Record(ARTIFACT_ADD, ImmutableMap.of(GROUP_ID, GROUP,
        ARTIFACT_ID, ARTIFACT,
        Record.VERSION, VERSION,
        Record.CLASSIFIER, "",
        FILE_EXTENSION, EXTENSION));
    
    // Start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadIndex = i;
      Future<String> result = Thread.ofVirtual().name("concurrent-gavce-test-" + i).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Verify we're running in a virtual thread
          Thread currentThread = Thread.currentThread();
          assertTrue(currentThread.isVirtual(), "Test should be running in a virtual thread");
          
          // Alternate between records with and without classifiers
          Record record = (threadIndex % 2 == 0) ? recordWithClassifier : recordWithoutClassifier;
          String result1 = gavceForRecord(record);
          
          return result1;
        } 
        finally {
          completionLatch.countDown();
        }
      });
      
      results.add(result);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Verify results
    String expectedWithClassifier = GROUP + ARTIFACT + VERSION + CLASSIFIER + ":" + EXTENSION;
    String expectedWithoutClassifier = GROUP + ARTIFACT + VERSION + "n/a" + ":" + EXTENSION;
    
    for (int i = 0; i < threadCount; i++) {
      String gavce = results.get(i).get();
      if (i % 2 == 0) {
        assertThat(gavce, is(equalTo(expectedWithClassifier)));
      } else {
        assertThat(gavce, is(equalTo(expectedWithoutClassifier)));
      }
    }
  }
}