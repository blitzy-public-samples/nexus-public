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
import static org.mockito.Mockito.when;

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
  void subjectRegistrationShouldAddAndRemoveSubjectFromMap() {
    assertThat(helper.subjects.size(), is(0));
    try (SubjectRegistration registration = helper.register(subject)) {
      assertThat(helper.subjects.size(), is(1));
      assertThat(helper.getSubject(registration.getId()), is(subject));
    }
    assertThat(helper.subjects.size(), is(0));
  }

  @Test
  void getSubjectShouldThrowExceptionWhenSubjectIdNotFound() {
    assertThrows(NullPointerException.class, () -> helper.getSubject(""));
  }
  
  @Test
  void concurrentSubjectRegistrationWithVirtualThreadsShouldWorkCorrectly() throws Exception {
    // Number of concurrent operations to perform
    int concurrentOperations = 1000;
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Synchronization aids
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create mock subjects for each thread
    List<Subject> mockSubjects = new ArrayList<>();
    for (int i = 0; i < concurrentOperations; i++) {
      Subject mockSubject = mock(Subject.class);
      when(mockSubject.toString()).thenReturn("MockSubject-" + i);
      mockSubjects.add(mockSubject);
    }
    
    // Submit tasks to register and retrieve subjects concurrently
    for (int i = 0; i < concurrentOperations; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Register the subject
          try (SubjectRegistration registration = helper.register(mockSubjects.get(index))) {
            // Verify the subject was registered correctly
            Subject retrievedSubject = helper.getSubject(registration.getId());
            if (retrievedSubject != mockSubjects.get(index)) {
              errorCount.incrementAndGet();
            }
          }
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete (with timeout)
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertThat("All operations should complete within the timeout", completed, is(true));
    assertThat("No errors should occur during concurrent operations", errorCount.get(), is(0));
    assertThat("All subjects should be unregistered", helper.subjects.size(), is(0));
  }
}
