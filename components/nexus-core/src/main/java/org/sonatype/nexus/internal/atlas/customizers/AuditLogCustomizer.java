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
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.supportzip.GeneratedContentSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import org.apache.commons.io.FileUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.LOW;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.AUDITLOG;

/**
 * Adds audit log files to support bundle.
 *
 * @since 3.16
 */
@Named
@Singleton
public class AuditLogCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{
  private final LogManager logManager;

  @Inject
  public AuditLogCustomizer(final LogManager logManager) {
    this.logManager = checkNotNull(logManager);
  }

  @Override
  public void customize(final SupportBundle supportBundle) {
    // add source for audit.log
    supportBundle.add(new GeneratedContentSourceSupport(AUDITLOG, "log/audit.log", LOW)
    {
      @Override
      protected void generate(final File file) {
        try {
          // Use Virtual Threads for I/O operations to improve performance
          Future<?> task = Thread.ofVirtual().name("audit-log-reader").start(() -> {
            try {
              InputStream is = logManager.getLogFileStream("audit.log", 0, Long.MAX_VALUE);
              if (is != null) {
                FileUtils.copyInputStreamToFile(is, file);
              }
              else {
                log.debug(STR."Not including missing audit.log file");
              }
            }
            catch (IOException e) {
              // Enhanced error handling with improved context
              log.debug(STR."Unable to include audit.log file: \{e.getMessage()}", e);
            }
            return null;
          });
          
          // Wait for the virtual thread to complete
          try {
            task.get();
          } 
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug(STR."Interrupted while processing audit.log file", e);
          }
          catch (ExecutionException e) {
            log.debug(STR."Error executing audit log processing: \{e.getCause().getMessage()}", e.getCause());
          }
        }
        catch (Exception e) {
          // Fault barrier pattern - centralized error handling
          log.debug(STR."Failed to process audit.log file: \{e.getMessage()}", e);
        }
      }
    });
  }
}