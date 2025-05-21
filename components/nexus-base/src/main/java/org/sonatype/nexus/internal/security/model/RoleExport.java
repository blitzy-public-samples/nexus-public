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
import java.util.concurrent.CompletableFuture;

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
 * Implements Virtual Threads for I/O operations during export/import.
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
   * 
   * @param file the file to export to
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void export(final File file) throws IOException {
    log.debug("Export CRole data to {} using Virtual Thread", file);
    
    // Use CompletableFuture to run the export operation asynchronously in a virtual thread
    try {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          List<CRole> roles = configuration.getRoles();
          exportToJson(roles, file);
        } catch (IOException e) {
          throw new RuntimeException("Error exporting CRole data", e);
        }
      }, Thread.ofVirtual().name("role-export-").factory());
      
      // Wait for the operation to complete
      future.join();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Restore CRole data from a JSON file using Virtual Threads for I/O operations.
   * 
   * @param file the file to restore from
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void restore(final File file) throws IOException {
    log.debug("Restoring CRole data from {} using Virtual Thread", file);
    
    // Use CompletableFuture to run the restore operation asynchronously in a virtual thread
    try {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          importFromJson(file, CRoleData.class).forEach(configuration::addRole);
        } catch (IOException e) {
          throw new RuntimeException("Error restoring CRole data", e);
        }
      }, Thread.ofVirtual().name("role-restore-").factory());
      
      // Wait for the operation to complete
      future.join();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }
}