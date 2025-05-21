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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.supportzip.ExportSecurityData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

/**
 * Write/Read {@link CPrivilege} data to/from a JSON file using Java 21 features.
 * <p>
 * This implementation leverages Virtual Threads for I/O operations to improve performance
 * and scalability during export/import operations.
 *
 * @since 3.29
 */
@Named("privilegeExport")
@Singleton
public class PrivilegeExport
    extends JsonExporter
    implements ExportSecurityData, ImportData
{
  private final SecurityConfiguration configuration;
  
  // Virtual thread executor for I/O operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public PrivilegeExport(final SecurityConfiguration configuration) {
    this.configuration = configuration;
    // Create a virtual thread per task executor for I/O operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void export(final File file) throws IOException {
    log.debug("Export CPrivilege data to {}", file);
    
    // Get privileges from configuration
    List<CPrivilege> privileges = configuration.getPrivileges();
    
    try {
      // Use virtual threads for I/O operations to improve performance
      Future<?> exportTask = virtualThreadExecutor.submit(() -> {
        try {
          exportToJson(privileges, file);
        } 
        catch (IOException e) {
          log.error("Failed to export CPrivilege data to {}", file, e);
          throw new RuntimeException("Failed to export CPrivilege data", e);
        }
      });
      
      // Wait for the export task to complete
      exportTask.get();
    } 
    catch (Exception e) {
      log.error("Error during CPrivilege export", e);
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Failed to export CPrivilege data", e);
    }
  }

  @Override
  public void restore(final File file) throws IOException {
    log.debug("Restoring CPrivilege data from {}", file);
    
    try {
      // Use virtual threads for I/O operations to improve performance
      Future<List<CPrivilege>> importTask = virtualThreadExecutor.submit(() -> {
        try {
          return importFromJson(file, CPrivilegeData.class);
        } 
        catch (IOException e) {
          log.error("Failed to import CPrivilege data from {}", file, e);
          throw new RuntimeException("Failed to import CPrivilege data", e);
        }
      });
      
      // Wait for the import task to complete and add privileges to configuration
      importTask.get().forEach(configuration::addPrivilege);
    } 
    catch (Exception e) {
      log.error("Error during CPrivilege import", e);
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Failed to import CPrivilege data", e);
    }
  }
}