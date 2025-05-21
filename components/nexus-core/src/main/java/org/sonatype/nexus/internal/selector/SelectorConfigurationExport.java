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
package org.sonatype.nexus.internal.selector;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorConfigurationStore;
import org.sonatype.nexus.supportzip.ExportConfigData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

/**
 * Write/Read {@link SelectorConfiguration} data to/from a JSON file.
 * Uses Java 21 Virtual Threads for improved I/O performance during export/import operations.
 *
 * @since 3.29
 */
@Named("selectorConfigurationExport")
@Singleton
public class SelectorConfigurationExport
    extends JsonExporter
    implements ExportConfigData, ImportData
{
  private final SelectorConfigurationStore store;

  @Inject
  public SelectorConfigurationExport(final SelectorConfigurationStore store) {
    this.store = store;
  }

  @Override
  public void export(final File file) throws IOException {
    log.debug("Export SelectorConfiguration data to {}", file);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Retrieve configurations
      List<SelectorConfiguration> configurations = store.browse();
      
      // Submit export task to virtual thread executor
      Future<?> exportTask = executor.submit(() -> {
        try {
          exportToJson(configurations, file);
        } catch (IOException e) {
          throw new RuntimeException("Failed to export selector configurations", e);
        }
      });
      
      // Wait for export to complete
      try {
        exportTask.get();
      } catch (Exception e) {
        throw new IOException("Error during selector configuration export", e);
      }
    }
  }

  @Override
  public void restore(final File file) throws IOException {
    log.debug("Restoring SelectorConfiguration data from {}", file);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit import task to virtual thread executor
      Future<?> importTask = executor.submit(() -> {
        try {
          importFromJson(file, SelectorConfigurationData.class).forEach(store::create);
        } catch (IOException e) {
          throw new RuntimeException("Failed to import selector configurations", e);
        }
      });
      
      // Wait for import to complete
      try {
        importTask.get();
      } catch (Exception e) {
        throw new IOException("Error during selector configuration import", e);
      }
    }
  }
}