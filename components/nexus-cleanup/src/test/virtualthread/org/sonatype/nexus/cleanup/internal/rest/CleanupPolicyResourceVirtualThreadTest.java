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
package org.sonatype.nexus.cleanup.internal.rest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Provider;
import javax.ws.rs.core.Response;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.cleanup.config.CleanupPolicyConfiguration;
import org.sonatype.nexus.cleanup.internal.preview.CsvCleanupPreviewContentWriter;
import org.sonatype.nexus.cleanup.preview.CleanupPreviewHelper;
import org.sonatype.nexus.cleanup.rest.CleanupPolicyRequestValidator;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.singleton;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringEndsWith.endsWith;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.hamcrest.text.IsEmptyString.isEmptyOrNullString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link CleanupPolicyResource} REST endpoint's CSV preview feature under Virtual Threads.
 * Validates that REST operations maintain correct response formatting and header handling
 * when processing many simultaneous cleanup policy preview requests using Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class CleanupPolicyResourceVirtualThreadTest
    extends TestSupport
{
  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  @Mock
  private List<Format> formats;

  @Mock
  private Map<String, CleanupPolicyConfiguration> cleanupFormatConfigurationMap;

  @Mock
  private Provider<CleanupPreviewHelper> cleanupPreviewHelper;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private EventManager eventManager;

  @Mock
  private CsvCleanupPreviewContentWriter csvCleanupPreviewContentWriter;

  @Mock
  private CleanupPolicyRequestValidator cleanupPolicyValidator;

  @Mock
  private Format mockFormat;

  private Collection<CleanupPolicyRequestValidator> cleanupPolicyValidators;

  private CleanupPolicyResource underTest;

  private final String repositoryName = "test-repo";

  @BeforeEach
  public void setUp() throws Exception {
    when(cleanupFormatConfigurationMap.get("default")).thenReturn(mock(CleanupPolicyConfiguration.class));
    Repository repository = mock(Repository.class);
    when(repositoryManager.get(repositoryName)).thenReturn(repository);
    when(repository.getName()).thenReturn(repositoryName);
    when(repository.getFormat()).thenReturn(mockFormat);
    when(mockFormat.getValue()).thenReturn("test-format");
    cleanupPolicyValidators = singleton(cleanupPolicyValidator);

    underTest = new CleanupPolicyResource(
        cleanupPolicyStorage,
        formats,
        cleanupFormatConfigurationMap,
        cleanupPreviewHelper,
        repositoryManager,
        eventManager,
        true,
        csvCleanupPreviewContentWriter,
        cleanupPolicyValidators);
  }

  /**
   * Tests that the CSV preview endpoint correctly handles a single request with virtual threads.
   */
  @Test
  public void testSinglePreviewContentCsvWithVirtualThread() {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      Response response = executor.submit(() -> 
          underTest.previewContentCsv(null, repositoryName, null, null, null, null, null, null)).get();
      
      assertThat(response.getStatus(), is(200));
      String contentDisposition = response.getHeaderString("Content-Disposition");
      assertThat(contentDisposition, not(isEmptyOrNullString()));
      String expectedPrefix = "attachment; filename=CleanupPreview-" + repositoryName;
      assertThat(contentDisposition, startsWith(expectedPrefix));
      assertThat(contentDisposition, endsWith(".csv"));
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing virtual thread test", e);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the CSV preview endpoint correctly handles multiple concurrent requests with virtual threads.
   * This validates that the REST endpoint can scale efficiently with Virtual Threads when handling
   * high concurrency client requests.
   */
  @Test
  public void testConcurrentPreviewContentCsvWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrentRequests = 100;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent requests using virtual threads
      for (int i = 0; i < concurrentRequests; i++) {
        final String policyName = (i % 2 == 0) ? null : "policy-" + i;
        
        executor.submit(() -> {
          try {
            Response response = underTest.previewContentCsv(
                policyName, repositoryName, null, null, null, null, null, null);
            
            // Verify response status
            if (response.getStatus() == 200) {
              successCount.incrementAndGet();
            }
            else {
              errorCount.incrementAndGet();
            }
            
            // Verify content disposition header
            String contentDisposition = response.getHeaderString("Content-Disposition");
            if (contentDisposition != null && !contentDisposition.isEmpty() && 
                contentDisposition.endsWith(".csv")) {
              // Header format is correct
              String expectedPrefix = "attachment; filename=";
              if (policyName == null) {
                expectedPrefix += "CleanupPreview-" + repositoryName;
              }
              else {
                expectedPrefix += policyName + "-" + repositoryName;
              }
              
              if (!contentDisposition.startsWith(expectedPrefix)) {
                errorCount.incrementAndGet();
              }
            }
            else {
              errorCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual thread tasks to complete");
      
      // Verify results
      assertEquals(concurrentRequests, successCount.get(), "All requests should succeed");
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent execution");
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the CSV preview endpoint correctly handles a mix of policy name variations
   * when executed concurrently with virtual threads.
   */
  @Test
  public void testConcurrentPreviewContentCsvWithDifferentPolicyNames() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int concurrentRequests = 50;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Create a mix of policy names including null, empty, and various string values
      String[] policyNames = new String[concurrentRequests];
      for (int i = 0; i < concurrentRequests; i++) {
        if (i % 5 == 0) {
          policyNames[i] = null; // Null policy name
        }
        else if (i % 5 == 1) {
          policyNames[i] = ""; // Empty policy name
        }
        else if (i % 5 == 2) {
          policyNames[i] = "policy-" + i; // Normal policy name
        }
        else if (i % 5 == 3) {
          policyNames[i] = "very-long-policy-name-with-many-characters-" + i; // Long policy name
        }
        else {
          policyNames[i] = "special_policy!@#$%^&*()_+" + i; // Policy name with special characters
        }
      }
      
      // Submit concurrent requests with different policy names
      for (int i = 0; i < concurrentRequests; i++) {
        final String policyName = policyNames[i];
        
        executor.submit(() -> {
          try {
            Response response = underTest.previewContentCsv(
                policyName, repositoryName, null, null, null, null, null, null);
            
            // Verify response
            if (response.getStatus() == 200) {
              String contentDisposition = response.getHeaderString("Content-Disposition");
              if (contentDisposition != null && contentDisposition.endsWith(".csv")) {
                String expectedPrefix;
                if (policyName == null || policyName.isEmpty()) {
                  expectedPrefix = "attachment; filename=CleanupPreview-" + repositoryName;
                }
                else {
                  expectedPrefix = "attachment; filename=" + policyName + "-" + repositoryName;
                }
                
                if (contentDisposition.startsWith(expectedPrefix)) {
                  successCount.incrementAndGet();
                }
              }
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual thread tasks to complete");
      
      // Verify all requests were successful
      assertEquals(concurrentRequests, successCount.get(), 
          "All requests should succeed with correct content disposition headers");
    }
    finally {
      executor.shutdown();
    }
  }
}