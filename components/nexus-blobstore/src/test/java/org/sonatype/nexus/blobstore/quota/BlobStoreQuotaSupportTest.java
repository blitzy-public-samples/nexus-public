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
package org.sonatype.nexus.blobstore.quota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.test.common.virtualthread.VirtualThreadTestGroup;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BlobStoreQuotaSupportTest
    extends TestSupport
{

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreQuotaService quotaService;

  @Mock
  private Logger logger;

  @Test
  public void getLimitHandlesNumbersProperly() {
    BlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.attributes(BlobStoreQuotaSupport.ROOT_KEY).set(BlobStoreQuotaSupport.LIMIT_KEY, 1);
    assertThat(BlobStoreQuotaSupport.getLimit(config), is(1L));

    config.attributes(BlobStoreQuotaSupport.ROOT_KEY).set(BlobStoreQuotaSupport.LIMIT_KEY, 0);
    assertThat(BlobStoreQuotaSupport.getLimit(config), is(0L));

    config.attributes(BlobStoreQuotaSupport.ROOT_KEY).set(BlobStoreQuotaSupport.LIMIT_KEY, -1);
    assertThat(BlobStoreQuotaSupport.getLimit(config), is(-1L));
  }

  @Test
  public void getLimitHandlesErrorCases() {
    BlobStoreConfiguration config = new MockBlobStoreConfiguration();
    config.attributes(BlobStoreQuotaSupport.ROOT_KEY).set(BlobStoreQuotaSupport.LIMIT_KEY, null);
    
    assertThrows(IllegalArgumentException.class, () -> {
      BlobStoreQuotaSupport.getLimit(config);
    });
  }

  @Test
  public void passedQuotaLogsNothing() {
    var result = new BlobStoreQuotaResult(false, "name", "msg");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);

    BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);

    verify(logger, never()).error(anyString());
    verify(logger, never()).warn("msg");
  }

  @Test
  public void failedQuotaLogsResult() {
    // Using record pattern to extract fields directly
    var result = new BlobStoreQuotaResult(true, "name", "msg");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);

    BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);

    verify(logger, never()).error(anyString());
    verify(logger).warn("msg");
  }

  @Test
  public void quotaCheckJobExceptionsAreCaught() {
    when(blobStore.getBlobStoreConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStore.getBlobStoreConfiguration().getName()).thenReturn("testConfig");
    doThrow(new RuntimeException()).when(quotaService).checkQuota(blobStore);

    BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);

    // Using String Template for error message verification
    verify(logger).error(anyString(), anyString(), any(RuntimeException.class));
    verify(logger, never()).warn(anyString());
  }
  
  @Test
  public void quotaCheckJobWithRecordPattern() {
    // Using record pattern for direct field access
    var result = new BlobStoreQuotaResult(true, "testStore", "Quota exceeded");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);
    
    // When we extract using record pattern
    if (result instanceof BlobStoreQuotaResult(var exceeded, var name, var message)) {
      // Then we can use the extracted fields directly
      assertThat(exceeded, is(true));
      assertThat(name, is("testStore"));
      assertThat(message, is("Quota exceeded"));
    }
    
    BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
    
    // Using String Template for verification message
    verify(logger).warn(STR."\{result.getMessage()}");
  }
  
  @Test
  @VirtualThreadTestGroup
  public void quotaCheckJobWithVirtualThreads() {
    // Test quota processing with Virtual Threads
    var result = new BlobStoreQuotaResult(true, "virtualThreadStore", "Virtual Thread quota exceeded");
    when(quotaService.checkQuota(blobStore)).thenReturn(result);
    
    // Run the quota check job in a virtual thread context
    Thread.startVirtualThread(() -> {
      BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
    }).join();
    
    // Verify the warning was logged with the correct message using String Template
    verify(logger).warn(STR."\{result.getMessage()}");
  }
  
  @Test
  @VirtualThreadTestGroup
  public void quotaCheckJobExceptionsWithVirtualThreads() {
    // Configure mock for exception testing with virtual threads
    when(blobStore.getBlobStoreConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(blobStore.getBlobStoreConfiguration().getName()).thenReturn("virtualThreadConfig");
    var testException = new RuntimeException("Virtual Thread test exception");
    doThrow(testException).when(quotaService).checkQuota(blobStore);
    
    // Run the quota check job in a virtual thread context
    Thread.startVirtualThread(() -> {
      BlobStoreQuotaSupport.quotaCheckJob(blobStore, quotaService, logger);
    }).join();
    
    // Verify error was logged with String Template message
    verify(logger).error(contains("virtualThreadConfig"), eq("virtualThreadConfig"), eq(testException));
    verify(logger, never()).warn(anyString());
  }
}