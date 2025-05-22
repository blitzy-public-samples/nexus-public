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
package org.sonatype.stringtemplates;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.blobstore.api.tasks.ReconcileTaskConstants.UNABLE_TO_RUN_TASK_LOG_ERROR;

import org.junit.jupiter.api.Test;

/**
 * Tests for validating Java 21 String Templates in reconciliation task messages.
 * 
 * @since 3.60
 */
public class ReconciliationStringTemplatesTest
{
  @Test
  public void testSimpleReconciliationMessage() {
    String taskName = "blobstore-reconcile";
    
    // Traditional string formatting
    String traditionalMessage = String.format("Unable to run task %s", taskName);
    
    // Using String Templates
    String templateMessage = STR."Unable to run task \{taskName}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
    
    // Verify against constant
    String constantBasedMessage = String.format(UNABLE_TO_RUN_TASK_LOG_ERROR, taskName);
    String templateConstantMessage = STR."\{UNABLE_TO_RUN_TASK_LOG_ERROR.replace("%s", "")}\{taskName}";
    
    assertThat(templateConstantMessage, is(equalTo(constantBasedMessage)));
  }
  
  @Test
  public void testComplexReconciliationMessage() {
    String blobStoreName = "default";
    String blobStoreType = "file";
    long blobCount = 1234;
    long totalSize = 5678901234L;
    
    // Traditional string formatting with multiple variables
    String traditionalMessage = String.format(
        "Re-calculating size metrics on blob store '%s' of type '%s', count: %d, size: %d",
        blobStoreName, blobStoreType, blobCount, totalSize);
    
    // Using String Templates with multiple variables
    String templateMessage = STR."Re-calculating size metrics on blob store '\{blobStoreName}' of type '\{blobStoreType}', count: \{blobCount}, size: \{totalSize}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  @Test
  public void testConditionalReconciliationMessage() {
    String blobStoreName = "default";
    boolean includeSoftDeleted = true;
    
    // Traditional string formatting with conditional content
    String traditionalMessage = String.format(
        "Reconciling blob store '%s'%s",
        blobStoreName,
        includeSoftDeleted ? " including soft-deleted blobs" : "");
    
    // Using String Templates with conditional expression
    String templateMessage = STR."Reconciling blob store '\{blobStoreName}'\{includeSoftDeleted ? " including soft-deleted blobs" : ""}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
    
    // Test with different condition
    includeSoftDeleted = false;
    
    // Traditional string formatting with conditional content
    traditionalMessage = String.format(
        "Reconciling blob store '%s'%s",
        blobStoreName,
        includeSoftDeleted ? " including soft-deleted blobs" : "");
    
    // Using String Templates with conditional expression
    templateMessage = STR."Reconciling blob store '\{blobStoreName}'\{includeSoftDeleted ? " including soft-deleted blobs" : ""}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  @Test
  public void testProgressLogReconciliationMessage() {
    String blobStoreName = "default";
    long totalSize = 5678901234L;
    long totalCount = 1234;
    
    // Traditional string formatting for progress log message
    String traditionalMessage = String.format(
        "Re-calculating size metrics on blob store '%s', size : %d - blobs count : %d",
        blobStoreName, totalSize, totalCount);
    
    // Using String Templates for progress log message
    String templateMessage = STR."Re-calculating size metrics on blob store '\{blobStoreName}', size : \{totalSize} - blobs count : \{totalCount}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  @Test
  public void testErrorReconciliationMessage() {
    String blobStoreType = "file";
    String blobStoreName = "default";
    
    // Traditional string formatting for error message
    String traditionalMessage = String.format(
        "Exception during migrating metrics from properties to DB for %s:%s",
        blobStoreType, blobStoreName);
    
    // Using String Templates for error message
    String templateMessage = STR."Exception during migrating metrics from properties to DB for \{blobStoreType}:\{blobStoreName}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  @Test
  public void testWarningReconciliationMessage() {
    String blobStoreType = "file";
    String blobStoreName = "default";
    
    // Traditional string formatting for warning message
    String traditionalMessage = String.format(
        "Blob store %s:%s is not started, skipping it.",
        blobStoreType, blobStoreName);
    
    // Using String Templates for warning message
    String templateMessage = STR."Blob store \{blobStoreType}:\{blobStoreName} is not started, skipping it.";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  @Test
  public void testDebugReconciliationMessage() {
    String metricsFromDb = "BlobStoreMetricsEntity{blobCount=1234, totalSize=5678901234}";
    String blobStoreType = "file";
    String blobStoreName = "default";
    
    // Traditional string formatting for debug message
    String traditionalMessage = String.format(
        "Found metrics %s for %s:%s should be migrated to DB",
        metricsFromDb, blobStoreType, blobStoreName);
    
    // Using String Templates for debug message
    String templateMessage = STR."Found metrics \{metricsFromDb} for \{blobStoreType}:\{blobStoreName} should be migrated to DB";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
}