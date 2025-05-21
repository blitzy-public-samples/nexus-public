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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.internal.atlas.SupportZipGeneratorImpl;
import org.sonatype.nexus.supportzip.FileContentSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.OPTIONAL;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.ARCHIVEDLOG;

@Named
@Singleton
public class ArchivedLogCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{

  private final ApplicationDirectories applicationDirectories;

  @Inject
  public ArchivedLogCustomizer(final ApplicationDirectories applicationDirectories) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
  }

  @Override
  public void customize(final SupportBundle supportBundle) {
    int archivedLogSize = SupportZipGeneratorImpl.getArchivedLogSize();
    includeArchivedLogs(supportBundle, archivedLogSize);
  }

  /**
   * Checks if a file exists and includes it in the support bundle if it does.
   * Returns the file if it exists, null otherwise.
   */
  private File checkFileExists(final File file, final String prefix, final SupportBundle supportBundle) {
    if (file != null && file.exists()) {
      log.debug(STR."Including file: \{file}");
      supportBundle.add(
          new FileContentSourceSupport(ARCHIVEDLOG, STR."\{prefix}/\{file.getName()}", file, OPTIONAL));
      return file;
    }
    else {
      log.debug(STR."Skipping non-existent file: \{file}");
      return null;
    }
  }

  /**
   * Includes archived logs in the support bundle using virtual threads for concurrent file existence checks.
   */
  private void includeArchivedLogs(final SupportBundle supportBundle, final int archivedLogSize) {
    List<String> archivedLogList = archivedLogFormatter(archivedLogSize);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit file existence check tasks concurrently
      List<Future<File>> futures = archivedLogList.stream()
          .map(log -> executor.submit(() -> {
            File file = new File(applicationDirectories.getWorkDirectory(), log);
            return checkFileExists(file, "log/archived-logs/", supportBundle);
          }))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete (results are already added to supportBundle in checkFileExists)
      futures.forEach(future -> {
        try {
          future.get();
        } catch (Exception e) {
          log.warn("Error checking archived log file", e);
        }
      });
    }
  }

  /**
   * Formats archived log file paths using pattern matching and string templates.
   */
  private List<String> archivedLogFormatter(int archivedLogSize) {
    ArrayList<String> archivedLogList = new ArrayList<>();
    final String LOGEXT = ".log.gz";
    LocalDate currentDate = LocalDate.now();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    for (int i = 0; i <= archivedLogSize; i++) {
      // Use string templates instead of StringBuilder
      LocalDate previousDate = currentDate.minusDays(i);
      String formattedDate = previousDate.format(formatter);
      
      // Use pattern matching with switch expression if needed in the future
      // For now, using string templates for path construction
      String requestLog = STR."log/request-\{formattedDate}\{LOGEXT}";
      String auditLog = STR."log/audit/audit-\{formattedDate}\{LOGEXT}";
      String nexusLog = STR."log/nexus-\{formattedDate}\{LOGEXT}";

      archivedLogList.add(requestLog);
      archivedLogList.add(auditLog);
      archivedLogList.add(nexusLog);
    }
    return archivedLogList;
  }
}