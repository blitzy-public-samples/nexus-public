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
package org.sonatype.nexus.internal.security.model;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.supportzip.ExportSecurityData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

/**
 * Write/Read {@link CRole} data to/from a JSON file using Java 21 features.
 * <p>
 * Implements Virtual Threads for I/O operations during export/import to improve performance
 * and scalability when handling large datasets.
 *
 * @since 3.29
 */
@Named("roleExport")
@Singleton
public class RoleExport
    extends JsonExporter
    implements ExportSecurityData, ImportData
{
  private final SecurityConfiguration configuration;

  @Inject
  public RoleExport(final SecurityConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * Export CRole data to a JSON file using Virtual Threads for I/O operations.
   * <p>
   * This implementation leverages Java 21 Virtual Threads to handle the I/O-bound
   * export operation without blocking platform threads, allowing for better scalability
   * when exporting large datasets.
   *
   * @param file The file to export data to
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void export(final File file) throws IOException {
    log.debug("Export CRole data to {}", file);
    
    // Use Virtual Thread for I/O-bound operation
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      executor.submit(() -> {
        try {
          List<CRole> roles = configuration.getRoles();
          exportToJson(roles, file);
        } catch (IOException e) {
          log.error("Failed to export CRole data to {}: {}", file, e.getMessage(), e);
          throw new RuntimeException("Failed to export CRole data", e);
        }
        return null;
      }).get(); // Wait for completion
    } catch (Exception e) {
      throw new IOException("Error during CRole export", e);
    } finally {
      executor.close();
    }
  }

  /**
   * Restore CRole data from a JSON file using Virtual Threads for I/O operations.
   * <p>
   * This implementation leverages Java 21 Virtual Threads to handle the I/O-bound
   * import operation without blocking platform threads, allowing for better scalability
   * when importing large datasets.
   *
   * @param file The file to import data from
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void restore(final File file) throws IOException {
    log.debug("Restoring CRole data from {}", file);
    
    // Use Virtual Thread for I/O-bound operation
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      executor.submit(() -> {
        try {
          importFromJson(file, CRoleData.class).forEach(configuration::addRole);
        } catch (IOException e) {
          log.error("Failed to restore CRole data from {}: {}", file, e.getMessage(), e);
          throw new RuntimeException("Failed to restore CRole data", e);
        }
        return null;
      }).get(); // Wait for completion
    } catch (Exception e) {
      throw new IOException("Error during CRole import", e);
    } finally {
      executor.close();
    }
  }
}