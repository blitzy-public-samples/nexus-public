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
package org.sonatype.nexus.internal.atlas.customizers;

import java.io.File;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.ArrayList;
import java.util.Queue;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.logging.task.TaskLogHome;
import org.sonatype.nexus.supportzip.FileContentSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import com.google.common.annotations.VisibleForTesting;

import static java.lang.StringTemplate.STR;
import static java.time.Instant.ofEpochMilli;
import static org.apache.commons.io.FileUtils.listFiles;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.DEFAULT;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.TASKLOG;

/**
 * Adds log files to support bundle.
 *
 * @since 3.5
 */
@Named
@Singleton
public class TaskLogCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{
  private static final String[] EXTENSIONS = new String[]{"log"};

  @Override
  public void customize(final SupportBundle supportBundle) {
    Instant cutoff = ZonedDateTime.now().minusHours(24).toInstant();
    String taskLogHome = getTaskLogHome();
    
    if (taskLogHome != null) {
      File taskLogDir = new File(taskLogHome);
      if (!taskLogDir.exists() || !taskLogDir.isDirectory()) {
        log.debug(STR."Task log directory not found: \{taskLogHome}");
        return;
      }
      
      // Get all log files
      List<File> logFiles = new ArrayList<>(listFiles(taskLogDir, EXTENSIONS, false));
      
      // Use Virtual Threads for parallel processing of log files
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Queue<FileContentSourceSupport> contentSources = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        
        // Process each file in a separate virtual thread
        for (File file : logFiles) {
          futures.add(executor.submit(() -> {
            if (ofEpochMilli(file.lastModified()).isAfter(cutoff)) {
              contentSources.add(
                  new FileContentSourceSupport(TASKLOG, STR."log/tasks/\{file.getName()}", file, DEFAULT));
            } else {
              log.debug(STR."Skipping file [past 24 hours]: \{file}");
            }
          }));
        }
        
        // Wait for all tasks to complete
        for (Future<?> future : futures) {
          try {
            future.get();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(STR."Interrupted while processing log files: \{e.getMessage()}");
            break;
          } catch (ExecutionException e) {
            log.error(STR."Error processing log file: \{e.getCause().getMessage()}", e.getCause());
          }
        }
        
        // Add all collected content sources to the support bundle
        contentSources.forEach(supportBundle::add);
      }
    }
  }

  @VisibleForTesting
  protected String getTaskLogHome() {
    return TaskLogHome.getTaskLogsHome();
  }
}