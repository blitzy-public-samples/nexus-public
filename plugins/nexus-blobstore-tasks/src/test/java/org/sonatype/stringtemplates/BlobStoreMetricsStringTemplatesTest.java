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

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import static java.lang.StringTemplate.STR;
import static java.lang.StringTemplate.FMT;

/**
 * Tests for validating Java 21 String Templates in metrics reporting messages.
 * 
 * This test class validates that metrics-related messages are correctly formatted using
 * Java 21 String Templates, ensuring proper variable interpolation, consistent formatting
 * of numeric values, and improved readability. The tests verify that metrics messages
 * maintain their semantic meaning while benefiting from the more concise and maintainable
 * String Template syntax, particularly for messages that include formatted numbers, units,
 * and calculated values.
 * 
 * Java 21 String Templates provide a more readable and maintainable way to format strings
 * compared to traditional String.format() or concatenation approaches. This is especially
 * valuable for metrics reporting where messages often include multiple numeric values with
 * specific formatting requirements.
 * 
 * @since 3.60
 */
public class BlobStoreMetricsStringTemplatesTest
    extends TestSupport
{
  private static final String BLOBSTORE_NAME = "test-blobstore";
  
  /**
   * Tests that String Templates can be used for simple metrics messages.
   */
  @Test
  public void testSimpleMetricsStringTemplates() {
    // Simple metrics message with a single variable
    String traditionalFormat = String.format("Blob store '%s' metrics initialized", BLOBSTORE_NAME);
    String templateFormat = STR."Blob store '\{BLOBSTORE_NAME}' metrics initialized";
    
    assertEquals("String Template should produce equivalent output for simple messages", 
        traditionalFormat, templateFormat);
    
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates produce equivalent output to traditional String.format() for read metrics.
   * This test validates the metrics message format used in PerformanceLogger.logRead().
   */
  @Test
  public void testReadMetricsStringTemplates() {
    long bytes = 1024 * 1024; // 1 MB
    long nanos = 500_000_000; // 500 ms
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;
    
    // Traditional format string approach (as used in PerformanceLogger)
    String traditionalFormat = String.format("blobstore %s: %d bytes read in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);
    
    // Java 21 String Template approach
    String templateFormat = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes read in \{millis} ms (\{mbPerSecond} mb/s)";
    
    assertEquals("String Template should produce equivalent output to String.format", 
        traditionalFormat, templateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates produce equivalent output to traditional String.format() for write metrics.
   * This test validates the metrics message format used in PerformanceLogger.logCreate().
   */
  @Test
  public void testWriteMetricsStringTemplates() {
    long bytes = 2 * 1024 * 1024; // 2 MB
    long nanos = 750_000_000; // 750 ms
    double millis = ((double) nanos) / 1e6d;
    double mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;
    
    // Traditional format string approach (as used in PerformanceLogger)
    String traditionalFormat = String.format("blobstore %s: %d bytes written in %g ms (%g mb/s)", 
        BLOBSTORE_NAME, bytes, millis, mbPerSecond);
    
    // Java 21 String Template approach
    String templateFormat = STR."blobstore \{BLOBSTORE_NAME}: \{bytes} bytes written in \{millis} ms (\{mbPerSecond} mb/s)";
    
    assertEquals("String Template should produce equivalent output to String.format", 
        traditionalFormat, templateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates produce equivalent output to traditional String.format() for delete metrics.
   * This test validates the metrics message format used in PerformanceLogger.logDelete().
   */
  @Test
  public void testDeleteMetricsStringTemplates() {
    long nanos = 250_000_000; // 250 ms
    double millis = ((double) nanos) / 1e6d;
    
    // Traditional format string approach (as used in PerformanceLogger)
    String traditionalFormat = String.format("blobstore %s: blob deleted in %g ms", 
        BLOBSTORE_NAME, millis);
    
    // Java 21 String Template approach
    String templateFormat = STR."blobstore \{BLOBSTORE_NAME}: blob deleted in \{millis} ms";
    
    assertEquals("String Template should produce equivalent output to String.format", 
        traditionalFormat, templateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates produce equivalent output to traditional String.format() for task progress metrics.
   * This test validates the metrics message format used in RecalculateBlobStoreSizeTask progress logging.
   */
  @Test
  public void testTaskProgressMetricsStringTemplates() {
    long totalSize = 1024 * 1024 * 1024; // 1 GB
    long totalCount = 1000;
    
    // Traditional format string approach (as used in RecalculateBlobStoreSizeTask)
    String traditionalFormat = String.format("Re-calculating size metrics on blob store '%s', size : %d - blobs count : %d",
        BLOBSTORE_NAME, totalSize, totalCount);
    
    // Java 21 String Template approach
    String templateFormat = STR."Re-calculating size metrics on blob store '\{BLOBSTORE_NAME}', size : \{totalSize} - blobs count : \{totalCount}";
    
    assertEquals("String Template should produce equivalent output to String.format", 
        traditionalFormat, templateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates handle numeric formatting correctly for large values.
   * This test validates that String Templates can correctly format large numeric values
   * that are common in blobstore metrics (GB-sized storage, millions of blobs).
   */
  @Test
  public void testLargeNumericValuesInTemplates() {
    long largeBytes = 1024L * 1024L * 1024L * 10L; // 10 GB
    long largeCount = 1_000_000; // 1 million
    
    // Traditional format string approach
    String traditionalFormat = String.format("blobstore %s: processed %d bytes across %d blobs", 
        BLOBSTORE_NAME, largeBytes, largeCount);
    
    // Java 21 String Template approach
    String templateFormat = STR."blobstore \{BLOBSTORE_NAME}: processed \{largeBytes} bytes across \{largeCount} blobs";
    
    assertEquals("String Template should handle large numeric values correctly", 
        traditionalFormat, templateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates handle floating point values with proper precision.
   * This test validates that String Templates with the FMT processor can correctly format
   * floating point values with specific precision, which is common in metrics reporting.
   */
  @Test
  public void testFloatingPointPrecisionInTemplates() {
    double percentage = 99.9876;
    double ratio = 0.12345;
    
    // Traditional format string approach with specific precision
    String traditionalFormat = String.format("blobstore %s: completed %.2f%% with efficiency ratio of %.4f", 
        BLOBSTORE_NAME, percentage, ratio);
    
    // Java 21 String Template approach with FMT processor for formatting
    String templateFormat = FMT."blobstore \{BLOBSTORE_NAME}: completed %.2f\{percentage}% with efficiency ratio of %.4f\{ratio}";
    
    assertEquals("String Template with FMT processor should handle floating point precision correctly", 
        traditionalFormat, templateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
  }
  
  /**
   * Tests that String Templates can be used for complex metrics messages with multiple variables and calculations.
   * This test validates that String Templates can handle complex metrics reporting scenarios with
   * multiple variables, calculations, and formatting requirements.
   */
  @Test
  public void testComplexMetricsMessageTemplates() {
    long totalBytes = 5 * 1024 * 1024 * 1024L; // 5 GB
    long usedBytes = 3 * 1024 * 1024 * 1024L; // 3 GB
    long availableBytes = totalBytes - usedBytes;
    double usagePercentage = ((double) usedBytes / totalBytes) * 100.0;
    
    // Traditional format string approach
    String traditionalFormat = String.format(
        "blobstore %s: total capacity: %d bytes, used: %d bytes (%.2f%%), available: %d bytes", 
        BLOBSTORE_NAME, totalBytes, usedBytes, usagePercentage, availableBytes);
    
    // Java 21 String Template approach with inline calculation
    String templateFormat = STR."blobstore \{BLOBSTORE_NAME}: total capacity: \{totalBytes} bytes, " + 
        "used: \{usedBytes} bytes (\{String.format("%.2f", usagePercentage)}%), available: \{availableBytes} bytes";
    
    assertEquals("String Template should handle complex metrics messages with calculations", 
        traditionalFormat, templateFormat);
    
    // Alternative approach using FMT processor
    String fmtTemplateFormat = FMT."blobstore \{BLOBSTORE_NAME}: total capacity: \{totalBytes} bytes, " + 
        "used: \{usedBytes} bytes (%.2f\{usagePercentage}%), available: \{availableBytes} bytes";
    
    assertEquals("FMT processor should handle complex metrics messages with formatting", 
        traditionalFormat, fmtTemplateFormat);
    
    // Log the formatted strings for visual comparison
    log.info("Traditional format: {}", traditionalFormat);
    log.info("Template format: {}", templateFormat);
    log.info("FMT template format: {}", fmtTemplateFormat);
  }
}