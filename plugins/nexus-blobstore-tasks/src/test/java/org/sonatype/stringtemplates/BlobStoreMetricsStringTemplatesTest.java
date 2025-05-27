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

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobMetrics;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * Tests for validating Java 21 String Templates in metrics reporting messages.
 *
 * @since 3.60
 */
public class BlobStoreMetricsStringTemplatesTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME = "test-blobstore";

  @Mock
  private Blob blob;

  @Mock
  private BlobMetrics blobMetrics;
  
  @Before
  public void setUp() {
    when(blob.getMetrics()).thenReturn(blobMetrics);
  }

  @Test
  public void testReadMetricsStringTemplate() {
    // Test parameters
    long bytes = 1024 * 1024; // 1MB
    long nanos = 500_000_000; // 500ms
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;

    // Traditional format string
    String traditional = format("blobstore %s: %d bytes read in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);

    // Java 21 String Template equivalent
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes read in \{millis} ms (\{mbPerSecond} mb/s)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testWriteMetricsStringTemplate() {
    // Test parameters
    long bytes = 2048 * 1024; // 2MB
    long nanos = 750_000_000; // 750ms
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;

    // Setup mock
    when(blob.getMetrics()).thenReturn(blobMetrics);
    when(blobMetrics.getContentSize()).thenReturn(bytes);

    // Traditional format string
    String traditional = format("blobstore %s: %d bytes written in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);

    // Java 21 String Template equivalent
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes written in \{millis} ms (\{mbPerSecond} mb/s)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testDeleteMetricsStringTemplate() {
    // Test parameters
    long nanos = 100_000_000; // 100ms
    double millis = ((double) nanos) / 1e6d;

    // Traditional format string
    String traditional = format("blobstore %s: blob deleted in %g ms", BLOBSTORE_NAME, millis);

    // Java 21 String Template equivalent
    String template = STR."blobstore \{BLOBSTORE_NAME}: blob deleted in \{millis} ms";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testRecalculateTaskProgressStringTemplate() {
    // Test parameters
    String blobStoreName = "test-blobstore";
    long totalSize = 1024 * 1024 * 1024; // 1GB
    long totalCount = 1000;

    // Traditional format string (using SLF4J style)
    String traditional = format("Re-calculating size metrics on blob store '%s', size : %d - blobs count : %d", 
        blobStoreName, totalSize, totalCount);

    // Java 21 String Template equivalent
    String template = STR."Re-calculating size metrics on blob store '\{blobStoreName}', size : \{totalSize} - blobs count : \{totalCount}";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testZeroValuesStringTemplate() {
    // Test parameters with zero values
    long bytes = 0;
    long nanos = 0;
    double millis = 0d;
    double mbPerSecond = Double.NaN;

    // Traditional format string
    String traditional = format("blobstore %s: %d bytes read in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);

    // Java 21 String Template equivalent
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes read in \{millis} ms (\{mbPerSecond} mb/s)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testLargeValuesStringTemplate() {
    // Test parameters with large values
    long bytes = 1024L * 1024L * 1024L * 10L; // 10GB
    long nanos = 5_000_000_000L; // 5 seconds
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;

    // Traditional format string
    String traditional = format("blobstore %s: %d bytes read in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);

    // Java 21 String Template equivalent
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes read in \{millis} ms (\{mbPerSecond} mb/s)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testFormattedNumericValuesStringTemplate() {
    // Test parameters
    long bytes = 1024 * 1024; // 1MB
    long nanos = 500_000_000; // 500ms
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;

    // Traditional format string with explicit formatting
    String traditional = format("blobstore %s: %d bytes read in %.2f ms (%.2f mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);

    // Java 21 String Template equivalent with explicit formatting
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes read in \{String.format("%.2f", millis)} ms (\{String.format("%.2f", mbPerSecond)} mb/s)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testMultipleOccurrencesStringTemplate() {
    // Test string with multiple occurrences of the same variable
    String blobStoreName = "test-blobstore";
    
    // Traditional format string
    String traditional = format("Blob store '%s' is processing metrics. Blob store '%s' has completed.", 
        blobStoreName, blobStoreName);

    // Java 21 String Template equivalent
    String template = STR."Blob store '\{blobStoreName}' is processing metrics. Blob store '\{blobStoreName}' has completed.";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testComplexExpressionStringTemplate() {
    // Test parameters
    long bytes = 1536 * 1024; // 1.5MB
    long nanos = 250_000_000; // 250ms
    
    // Traditional format string with calculation in the format
    String traditional = format("blobstore %s: %d bytes (%.2f MB) processed in %.2f seconds", 
        BLOBSTORE_NAME, bytes, bytes / (1024.0 * 1024.0), nanos / 1_000_000_000.0);

    // Java 21 String Template equivalent with inline expressions
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes (\{String.format("%.2f", bytes / (1024.0 * 1024.0))} MB) processed in \{String.format("%.2f", nanos / 1_000_000_000.0)} seconds";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testStringTemplateWithConditionalExpression() {
    // Test parameters
    long bytes = 1024 * 1024; // 1MB
    long nanos = 500_000_000; // 500ms
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;
    boolean isRead = true;

    // Traditional format string with conditional logic
    String operation = isRead ? "read" : "written";
    String traditional = format("blobstore %s: %d bytes %s in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, operation, millis, mbPerSecond);

    // Java 21 String Template equivalent with conditional expression
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes \{isRead ? "read" : "written"} in \{millis} ms (\{mbPerSecond} mb/s)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testStringTemplateWithMethodCall() {
    // Test parameters
    long bytes = 1024 * 1024 * 5; // 5MB
    
    // Traditional format string with method call
    String traditional = format("blobstore %s: %d bytes (%s)", 
        BLOBSTORE_NAME, bytes, formatSize(bytes));

    // Java 21 String Template equivalent with method call
    String template = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes (\{formatSize(bytes)})";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testStringTemplateWithNestedTemplates() {
    // Test parameters
    String blobStoreName = "test-blobstore";
    long totalSize = 1024 * 1024 * 1024; // 1GB
    long totalCount = 1000;
    
    // Create nested template for the size part
    String sizeInfo = STR."size : \{totalSize} - blobs count : \{totalCount}";
    
    // Traditional format string
    String traditional = format("Re-calculating size metrics on blob store '%s', %s", 
        blobStoreName, String.format("size : %d - blobs count : %d", totalSize, totalCount));

    // Java 21 String Template with nested template
    String template = STR."Re-calculating size metrics on blob store '\{blobStoreName}', \{sizeInfo}";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }
  
  @Test
  public void testStringTemplateWithMultilineOutput() {
    // Test parameters
    String blobStoreName = "test-blobstore";
    long totalSize = 1024 * 1024 * 1024; // 1GB
    long totalCount = 1000;
    
    // Traditional multi-line format string
    String traditional = format("Blob Store: %s\n" +
                              "Total Size: %d bytes\n" +
                              "Total Count: %d items", 
                              blobStoreName, totalSize, totalCount);

    // Java 21 String Template equivalent with multi-line output
    String template = STR."Blob Store: \{blobStoreName}\n" + 
                     STR."Total Size: \{totalSize} bytes\n" +
                     STR."Total Count: \{totalCount} items";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testStringTemplateWithEscapedBraces() {
    // Test parameters
    String blobStoreName = "test-blobstore";
    
    // Traditional format string with braces
    String traditional = format("Blob store '%s' {contains braces} in the message", blobStoreName);

    // Java 21 String Template equivalent with escaped braces
    String template = STR."Blob store '\{blobStoreName}' {contains braces} in the message";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  @Test
  public void testStringTemplateWithSpecialCharacters() {
    // Test parameters
    String blobStoreName = "test-blobstore";
    long bytes = 1024;
    
    // Traditional format string with special characters
    String traditional = format("blobstore %s: %d bytes (100%% complete)", blobStoreName, bytes);

    // Java 21 String Template equivalent with special characters
    String template = STR."blobstore \{blobStoreName}: \{bytes} bytes (100% complete)";

    // Verify equivalence
    assertThat(template, equalTo(traditional));
  }

  /**
   * Helper method to format size in human-readable form
   */
  private String formatSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    else if (bytes < 1024 * 1024) {
      return String.format("%.2f KB", bytes / 1024.0);
    }
    else if (bytes < 1024 * 1024 * 1024) {
      return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
    else {
      return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
  }
}