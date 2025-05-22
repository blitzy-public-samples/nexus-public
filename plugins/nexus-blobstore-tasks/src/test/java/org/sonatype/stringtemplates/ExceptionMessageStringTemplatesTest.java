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

import java.util.StringJoiner;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.tasks.ReconcileTaskConstants;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests to validate the use of Java 21 String Templates in exception messages.
 * 
 * These tests demonstrate how String Templates can improve the readability and maintainability
 * of exception messages compared to traditional string concatenation or String.format().
 */
public class ExceptionMessageStringTemplatesTest
    extends TestSupport
{
  private static final String BLOB_ID_VALUE = "test-blob-id";
  private static final String TASK_NAME = "reconcile-task";
  private static final String OPERATION = "blob reconciliation";
  
  /**
   * Test that demonstrates how String Templates can be used to create exception messages
   * with improved readability compared to traditional string concatenation.
   */
  @Test
  public void testStringTemplatesForExceptionMessages() {
    BlobId blobId = new BlobId(BLOB_ID_VALUE);
    String errorMessage = "Failed to process blob";
    
    // Traditional approach using string concatenation
    String traditionalMessage = "Error during operation: " + OPERATION + ", for blob: " + blobId + ", details: " + errorMessage;
    
    // Using String Templates
    String templateMessage = STR."Error during operation: \{OPERATION}, for blob: \{blobId}, details: \{errorMessage}";
    
    // Verify both approaches produce equivalent results
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
    
    // Create exceptions with both message styles
    BlobStoreException traditionalException = new BlobStoreException(traditionalMessage, blobId);
    BlobStoreException templateException = new BlobStoreException(templateMessage, blobId);
    
    // Verify exceptions have equivalent messages
    assertThat(templateException.getMessage(), is(equalTo(traditionalException.getMessage())));
  }
  
  /**
   * Test that demonstrates how String Templates can be used as an alternative to String.format()
   * for creating formatted exception messages with improved readability.
   */
  @Test
  public void testStringTemplatesVsStringFormat() {
    BlobId blobId = new BlobId(BLOB_ID_VALUE);
    int attemptCount = 3;
    long timeoutMs = 5000L;
    
    // Traditional approach using String.format
    String formatMessage = String.format("Failed to process blob %s after %d attempts (timeout: %d ms)", 
        blobId, attemptCount, timeoutMs);
    
    // Using String Templates
    String templateMessage = STR."Failed to process blob \{blobId} after \{attemptCount} attempts (timeout: \{timeoutMs} ms)";
    
    // Verify both approaches produce equivalent results
    assertThat(templateMessage, is(equalTo(formatMessage)));
    
    // Create exceptions with both message styles
    BlobStoreException formatException = new BlobStoreException(formatMessage, blobId);
    BlobStoreException templateException = new BlobStoreException(templateMessage, blobId);
    
    // Verify exceptions have equivalent messages
    assertThat(templateException.getMessage(), is(equalTo(formatException.getMessage())));
  }
  
  /**
   * Test that demonstrates how String Templates can be used as an alternative to StringJoiner
   * for creating complex exception messages with improved readability.
   */
  @Test
  public void testStringTemplatesVsStringJoiner() {
    BlobId blobId = new BlobId(BLOB_ID_VALUE);
    String repositoryName = "maven-central";
    String componentId = "org.example:artifact:1.0.0";
    
    // Traditional approach using StringJoiner
    StringJoiner joiner = new StringJoiner(", ");
    joiner.add("BlobId: " + blobId);
    joiner.add("Repository: " + repositoryName);
    joiner.add("Component: " + componentId);
    String joinerMessage = "Failed to process component: " + joiner;
    
    // Using String Templates
    String templateMessage = STR."Failed to process component: BlobId: \{blobId}, Repository: \{repositoryName}, Component: \{componentId}";
    
    // Verify both approaches produce equivalent results
    assertThat(templateMessage, is(equalTo(joinerMessage)));
    
    // Create exceptions with both message styles
    BlobStoreException joinerException = new BlobStoreException(joinerMessage, blobId);
    BlobStoreException templateException = new BlobStoreException(templateMessage, blobId);
    
    // Verify exceptions have equivalent messages
    assertThat(templateException.getMessage(), is(equalTo(joinerException.getMessage())));
  }
  
  /**
   * Test that demonstrates how String Templates can be used with nested exceptions
   * to create clear and readable error messages that include cause information.
   */
  @Test
  public void testStringTemplatesWithNestedExceptions() {
    BlobId blobId = new BlobId(BLOB_ID_VALUE);
    String errorMessage = "I/O error reading blob data";
    Exception cause = new java.io.IOException(errorMessage);
    
    // Traditional approach using string concatenation with nested exception
    String traditionalMessage = "Failed to process blob: " + blobId + ", cause: " + cause.getMessage();
    
    // Using String Templates with nested exception
    String templateMessage = STR."Failed to process blob: \{blobId}, cause: \{cause.getMessage()}";
    
    // Verify both approaches produce equivalent results
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
    
    // Create exceptions with both message styles
    BlobStoreException traditionalException = new BlobStoreException(traditionalMessage, cause, blobId);
    BlobStoreException templateException = new BlobStoreException(templateMessage, cause, blobId);
    
    // Verify exceptions have equivalent messages and cause information
    assertThat(templateException.getMessage(), containsString(blobId.toString()));
    assertThat(templateException.getMessage(), containsString(errorMessage));
    assertThat(templateException.getCause(), is(notNullValue()));
    assertThat(templateException.getCause().getMessage(), is(equalTo(errorMessage)));
  }
  
  /**
   * Test that demonstrates how String Templates can be used to improve the readability
   * of constant error messages defined in the codebase.
   */
  @Test
  public void testStringTemplatesForConstantErrorMessages() {
    // Traditional constant message from ReconcileTaskConstants
    String traditionalConstant = ReconcileTaskConstants.UNABLE_TO_RUN_TASK_LOG_ERROR;
    
    // Equivalent message using String Templates (if it were defined this way)
    String templateConstant = STR."Unable to run task \{}";
    
    // Verify both approaches produce equivalent results when used with a parameter
    String traditionalMessage = String.format(traditionalConstant, TASK_NAME);
    String templateMessage = STR."Unable to run task \{TASK_NAME}";
    
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
  }
  
  /**
   * Test that demonstrates how String Templates can be used with conditional expressions
   * to create dynamic exception messages based on runtime conditions.
   */
  @Test
  public void testStringTemplatesWithConditionalExpressions() {
    BlobId blobId = new BlobId(BLOB_ID_VALUE);
    boolean isTemporary = true;
    
    // Traditional approach using conditional concatenation
    String traditionalMessage = "Processing blob: " + blobId + 
        (isTemporary ? " (temporary)" : " (permanent)");
    
    // Using String Templates with conditional expression
    String templateMessage = STR."Processing blob: \{blobId} \{isTemporary ? "(temporary)" : "(permanent)"}";
    
    // Verify both approaches produce equivalent results
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
    assertThat(templateMessage, containsString("(temporary)"));
    
    // Test with different condition
    isTemporary = false;
    
    // Traditional approach using conditional concatenation
    traditionalMessage = "Processing blob: " + blobId + 
        (isTemporary ? " (temporary)" : " (permanent)");
    
    // Using String Templates with conditional expression
    templateMessage = STR."Processing blob: \{blobId} \{isTemporary ? "(temporary)" : "(permanent)"}";
    
    // Verify both approaches produce equivalent results
    assertThat(templateMessage, is(equalTo(traditionalMessage)));
    assertThat(templateMessage, containsString("(permanent)"));
  }
}