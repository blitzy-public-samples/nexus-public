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
package org.sonatype.nexus.script.plugin.internal;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.supportzip.ExportConfigData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

/**
 * Write/Read {@link Script} data to/from a JSON file using Java 21 features.
 *
 * @since 3.29
 */
@Named("scriptExport")
@Singleton
public class ScriptExport
    extends JsonExporter
    implements ExportConfigData, ImportData
{
  private final ScriptStore store;
  private final ExecutorService ioExecutor;

  @Inject
  public ScriptExport(final ScriptStore store) {
    this.store = store;
    // Use virtual threads for I/O operations
    this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void export(final File file) throws IOException {
    log.debug("Export Script data to {}", file);
    List<Script> scripts = store.list();
    
    try {
      // Use virtual thread for I/O operation
      ioExecutor.submit(() -> {
        try {
          exportToJson(scripts, file);
        } catch (IOException e) {
          log.error(STR."Error exporting script data to \{file}: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).get();
    } catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException(STR."Failed to export script data: \{e.getMessage()}", e);
    }
  }

  @Override
  public void restore(final File file) throws IOException {
    log.debug("Restoring Script data from {}", file);
    
    try {
      // Use virtual thread for I/O operation
      ioExecutor.submit(() -> {
        try {
          importFromJson(file, ScriptData.class).forEach(store::create);
        } catch (IOException e) {
          log.error(STR."Error importing script data from \{file}: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).get();
    } catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException(STR."Failed to restore script data: \{e.getMessage()}", e);
    }
  }
  
  /**
   * Shutdown the executor service when the component is stopped.
   */
  @Override
  protected void doStop() throws Exception {
    if (ioExecutor != null && !ioExecutor.isShutdown()) {
      ioExecutor.shutdown();
    }
    super.doStop();
  }
}