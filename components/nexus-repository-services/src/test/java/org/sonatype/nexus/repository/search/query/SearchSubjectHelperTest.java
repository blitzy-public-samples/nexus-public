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
package org.sonatype.nexus.repository.search.query;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.search.query.SearchSubjectHelper.SubjectRegistration;

import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SearchSubjectHelperTest
    extends TestSupport
{
  @Mock
  Subject subject;

  SearchSubjectHelper helper;

  @BeforeEach
  void setup() {
    helper = new SearchSubjectHelper();
  }

  @Test
  void registrationShouldAddAndRemoveSubject() {
    assertThat(helper.subjects.size(), is(0));
    try (SubjectRegistration registration = helper.register(subject)) {
      assertThat(helper.subjects.size(), is(1));
      assertThat(helper.getSubject(registration.getId()), is(subject));
    }
    assertThat(helper.subjects.size(), is(0));
  }

  @Test
  void getSubjectShouldThrowExceptionWhenSubjectNotFound() {
    assertThrows(NullPointerException.class, () -> helper.getSubject(""));
  }

  @Test
  void concurrentSubjectHandlingWithVirtualThreads() throws Exception {
    // Number of virtual threads to create
    int threadCount = 1000;
    
    // Use CountDownLatch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Track any errors that occur during execution
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Store registrations to verify later
    List<String> registrationIds = new ArrayList<>(threadCount);
    
    try {
      // Submit tasks to register subjects concurrently
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Create a mock subject for each thread
            Subject threadSubject = mock(Subject.class);
            
            // Register the subject and store the ID
            try (SubjectRegistration registration = helper.register(threadSubject)) {
              String id = registration.getId();
              
              // Verify we can retrieve the subject
              Subject retrieved = helper.getSubject(id);
              
              // Verify it's the same subject we registered
              if (retrieved != threadSubject) {
                errorCount.incrementAndGet();
              }
              
              // Add the ID to our list for verification
              synchronized (registrationIds) {
                registrationIds.add(id);
              }
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread test", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify all threads completed
      assertThat("All virtual threads should complete in time", completed, is(true));
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent execution", errorCount.get(), is(0));
      
      // Verify we collected the expected number of registration IDs
      assertThat("Should have collected all registration IDs", registrationIds.size(), is(threadCount));
      
      // Verify all subjects were properly unregistered (map should be empty)
      assertThat("All subjects should be unregistered", helper.subjects.size(), is(0));
    } 
    finally {
      executor.shutdown();
    }
  }
}