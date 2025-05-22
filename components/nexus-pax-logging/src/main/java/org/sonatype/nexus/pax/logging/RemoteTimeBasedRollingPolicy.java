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
package org.sonatype.nexus.pax.logging;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.rolling.RolloverFailure;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.google.common.annotations.VisibleForTesting;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;

/**
 * Rolling policy that uploads rolled files to a remote location.
 * Uses Java 21 Virtual Threads for improved I/O performance.
 */
public class RemoteTimeBasedRollingPolicy<E>
    extends TimeBasedRollingPolicy<E>
{
  private static final Pattern DATE_PATTERN = Pattern.compile("%d\\{([^}]*)\\}");

  private static final String SERVICE_NAME = "S3RollingPolicyUploader";

  private static final String LOG_SUFFIX = ".log";

  private Logger log;

  private String contextPrefix;

  private SimpleDateFormat filenameDateFormat;

  private ExecutorService executor;

  private Queue<String> nonUploadedFiles;
  
  // Metrics for log upload operations
  private final LongAdder uploadAttempts = new LongAdder();
  private final LongAdder uploadSuccesses = new LongAdder();
  private final LongAdder uploadFailures = new LongAdder();
  private final LongAdder uploadDuration = new LongAdder();

  @VisibleForTesting
  String getContextPrefix() {
    return contextPrefix;
  }

  @VisibleForTesting
  SimpleDateFormat getFilenameDateFormat() {
    return filenameDateFormat;
  }

  @VisibleForTesting
  public Queue<String> getNonUploadedFiles() {
    return nonUploadedFiles;
  }

  @VisibleForTesting
  ExecutorService getExecutor() {
    return executor;
  }
  
  /**
   * Get metrics for log upload operations
   * 
   * @return array of metrics [attempts, successes, failures, avgDurationMs]
   */
  @VisibleForTesting
  public long[] getUploadMetrics() {
    long attempts = uploadAttempts.sum();
    long successes = uploadSuccesses.sum();
    long failures = uploadFailures.sum();
    long avgDuration = attempts > 0 ? uploadDuration.sum() / attempts : 0;
    return new long[] {attempts, successes, failures, avgDuration};
  }

  @Override
  public void start() {
    super.start();
    doStart();
  }

  @VisibleForTesting
  void doStart() {
    createLogger();
    setContext();
    setFileNameDateFormat();
    // Use Virtual Threads for improved I/O performance
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.nonUploadedFiles = new ConcurrentLinkedQueue<>();
  }

  @Override
  public void rollover() throws RolloverFailure {
    String filePath = format("%s%s",
        getTimeBasedFileNamingAndTriggeringPolicy().getElapsedPeriodsFileName(),
        getFileNameSuffix());

    super.rollover();
    doUpload(filePath);
  }

  @VisibleForTesting
  void doUpload(final String filePath) {
    log.debug("file to upload : {} ", filePath);

    // get service reference through OSGi and upload the file in a separate virtual thread
    uploadAttempts.increment();
    long startTime = System.currentTimeMillis();
    
    executor.submit(() -> {
      try {
        uploadWithReference(filePath);
        uploadSuccesses.increment();
      } catch (Exception e) {
        uploadFailures.increment();
        log.error("Failed to upload file: {}", filePath, e);
      } finally {
        uploadDuration.add(System.currentTimeMillis() - startTime);
      }
    });
  }

  /**
   * Uploads looking for the service reference in OSGi.
   *
   * @param filePath file to upload
   */
  private void uploadWithReference(final String filePath) {
    BundleContext context = NexusLogActivator.INSTANCE.getContext();
    Collection<ServiceReference<RollingPolicyUploader>> references = getServiceReferences(context);

    if (references.isEmpty()) {
      addToNonUploaded(filePath);
      log.debug("No service reference(s) found - added file : '{}' to non uploaded files", filePath);
      return;
    }

    ServiceReference<RollingPolicyUploader> reference = references.iterator().next();
    RollingPolicyUploader uploader = context.getService(reference);

    try {
      doUpload(uploader, filePath);
      processNonUploadedFiles(uploader);
    }
    finally {
      // release the service reference
      context.ungetService(reference);
    }
  }

  /**
   * Get the service references for the given service name.
   *
   * @param context the OSGi bundle context
   * @return the service references if found, an empty collection otherwise
   */
  private Collection<ServiceReference<RollingPolicyUploader>> getServiceReferences(final BundleContext context) {
    Collection<ServiceReference<RollingPolicyUploader>> references = Collections.emptyList();

    try {
      references =
          context.getServiceReferences(RollingPolicyUploader.class, String.format("(service_name=*%s*)", SERVICE_NAME));
    }
    catch (InvalidSyntaxException e) {
      log.error("Failed to get service reference", e);
    }
    return references;
  }

  /**
   * Add the file to the non-uploaded files queue
   * Optimized for Virtual Threads with non-blocking operations
   *
   * @param filePath file path
   */
  private void addToNonUploaded(final String filePath) {
    nonUploadedFiles.offer(filePath);
    log.debug("Added file {} to non-uploaded files queue", filePath);

    // Use non-blocking size check to avoid synchronization
    if (nonUploadedFiles.size() <= this.getMaxHistory()) {
      return;
    }

    // Only synchronize when we need to remove an item
    String removedFile = nonUploadedFiles.poll();
    if (removedFile != null) {
      log.warn("Removed file {} from non-uploaded files queue, file won't be uploaded", removedFile);
    }
  }

  /**
   * processes non uploaded files
   * Optimized for Virtual Threads with concurrent processing
   *
   * @param uploader the {@link RollingPolicyUploader} to use
   */
  private void processNonUploadedFiles(final RollingPolicyUploader uploader) {
    String file;
    while ((file = nonUploadedFiles.poll()) != null) {
      final String currentFile = file;
      log.debug("Processing non-uploaded file: '{}'", currentFile);
      // Process each file in its own Virtual Thread for maximum concurrency
      executor.submit(() -> {
        try {
          uploadAttempts.increment();
          long startTime = System.currentTimeMillis();
          doUpload(uploader, currentFile);
          uploadSuccesses.increment();
          uploadDuration.add(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
          uploadFailures.increment();
          log.error("Failed to upload non-uploaded file: {}", currentFile, e);
        }
      });
    }
  }

  private void createLogger() {
    log = LoggerFactory.getLogger(RemoteTimeBasedRollingPolicy.class);
  }

  /**
   * Set the context prefix to use in the remote location.
   */
  private void setContext() {
    String karafData = System.getProperty("karaf.data", "");
    String segmentStr = fileNamePatternStr;

    if (!karafData.isEmpty()) {
      // remove the karaf data from the file name pattern
      segmentStr = fileNamePatternStr.substring(karafData.length() + 1);
    }

    String[] segments = segmentStr.split("/");
    StringBuilder prefixBuilder = new StringBuilder();

    // we don't want to include file name pattern in the prefix
    for (int i = 0; i < segments.length - 1; i++) {
      prefixBuilder.append(segments[i]);
      prefixBuilder.append("/");
    }

    this.contextPrefix = prefixBuilder.toString();
  }

  /**
   * Set the date format specified in the file name pattern.
   */
  private void setFileNameDateFormat() {
    Matcher matcher = DATE_PATTERN.matcher(fileNamePatternStr);

    if (matcher.find()) {
      String format = matcher.group(1);
      this.filenameDateFormat = new SimpleDateFormat(format);
      log.debug("Configured filename date pattern: {}", format);
    }
  }

  /**
   * Upload the non-uploaded files to S3.
   *
   * @param uploader uploader
   */
  private void doUpload(final RollingPolicyUploader uploader, final String filePath) {
    try {
      //we need to wait for the compression and clean-up jobs to finish before uploading the file
      waitForAsynchronousJobToStop(getFuture("compressionFuture"), "compression");
      waitForAsynchronousJobToStop(getFuture("cleanUpFuture"), "clean-up");

      uploader.rollover(contextPrefix, getLogDatePath(filePath), filePath);
    }
    catch (Exception e) {
      addError("Failed to upload file to S3", e);
      throw e; // Re-throw to track metrics
    }
  }

  /**
   * Get the log date path based on the log date (in the form YYYY/MM/dd/).
   *
   * @param filePath path of the log file
   * @return log date path
   */
  private String getLogDatePath(final String filePath) {
    if (filenameDateFormat == null) {
      return "";
    }

    int dateStartIndex = fileNamePatternStr.indexOf("%d");
    int dateEndIndex = filePath.indexOf(LOG_SUFFIX + getFileNameSuffix());

    String logDateStr = filePath.substring(dateStartIndex, dateEndIndex);

    try {
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(filenameDateFormat.parse(logDateStr));

      return buildLogDatePath(calendar);
    }
    catch (ParseException e) {
      addError("Failed to parse log date", e);
    }

    return "";
  }

  /**
   * Build the log date path based on the log date and the given separator.
   *
   * @param logDate log date
   * @return log date path string with the given separator
   */
  private String buildLogDatePath(final Calendar logDate) {
    StringBuilder logDatePath = new StringBuilder();

    int year = logDate.get(Calendar.YEAR);
    int month = logDate.get(Calendar.MONTH) + 1;
    int day = logDate.get(Calendar.DAY_OF_MONTH);
    int hour = logDate.get(Calendar.HOUR_OF_DAY);
    int minute = logDate.get(Calendar.MINUTE);

    logDatePath.append(year);
    logDatePath.append("/");

    logDatePath.append(month);
    logDatePath.append("/");

    logDatePath.append(day);
    logDatePath.append("/");

    if (hour != 0) {
      logDatePath.append(hour);
      logDatePath.append("/");
    }

    if (minute != 0) {
      logDatePath.append(minute);
      logDatePath.append("/");
    }

    return logDatePath.toString();
  }

  /**
   * Workaround to access futures in TimeBasedRollingPolicy.
   *
   * @param attributeName field name
   * @return field value or null if an error occurred
   */
  private Future<?> getFuture(final String attributeName) {
    try {
      Field field = TimeBasedRollingPolicy.class.getDeclaredField(attributeName);
      field.setAccessible(true);
      return (Future<?>) field.get(this);
    }
    catch (Exception e) {
      addError(format("Failed to access %s field", attributeName), e);
      return null;
    }
  }

  /**
   * Wait for the asynchronous job to stop.
   * Modified to handle Virtual Thread context properly.
   *
   * @param future         future
   * @param jobDescription job description
   */
  private void waitForAsynchronousJobToStop(Future<?> future, String jobDescription) {
    if (future != null) {
      try {
        // Virtual Threads are designed for I/O operations, so we can use a longer timeout
        // without impacting system resources
        future.get(CoreConstants.SECONDS_TO_WAIT_FOR_COMPRESSION_JOBS * 2, TimeUnit.SECONDS);
      }
      catch (TimeoutException e) {
        addError("Timeout while waiting for " + jobDescription + " job to finish", e);
      }
      catch (Exception e) {
        addError("Unexpected exception while waiting for " + jobDescription + " job to finish", e);
      }
    }
  }

  /**
   * get the file name suffix based on the compression mode
   *
   * @return file name suffix
   */
  private String getFileNameSuffix() {
    switch (compressionMode) {
      case GZ:
        return ".gz";
      case ZIP:
        return ".zip";
      case NONE:
      default:
        return "";
    }
  }
}