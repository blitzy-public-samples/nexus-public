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
package org.sonatype.nexus.internal.email;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.supportzip.ExportConfigData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

/**
 * Write/Read {@link EmailConfiguration} data to/from a JSON file using Virtual Threads for I/O operations.
 *
 * @since 3.29
 */
@Named("emailConfigurationExport")
@Singleton
public class EmailConfigurationExport
    extends JsonExporter
    implements ExportConfigData, ImportData
{
  private final EmailConfigurationStore store;

  @Inject
  public EmailConfigurationExport(final EmailConfigurationStore store) {
    this.store = store;
  }

  @Override
  public void export(final File file) throws IOException {
    log.debug(STR."Exporting EmailConfiguration data to \{file}");
    
    try (var scope = new ShutdownOnFailure()) {
      var exportTask = scope.fork(() -> {
        EmailConfiguration configuration = store.load();
        exportObjectToJson(configuration, file);
        return true;
      });
      
      scope.join();           // Wait for the virtual thread to complete
      scope.throwIfFailed(); // Propagate any exceptions
      
      log.debug(STR."Successfully exported EmailConfiguration to \{file}");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(STR."Export operation was interrupted for \{file}", e);
    } catch (ExecutionException e) {
      throw new IOException(STR."Failed to export EmailConfiguration to \{file}", e.getCause());
    }
  }

  @Override
  public void restore(final File file) throws IOException {
    log.debug(STR."Restoring EmailConfiguration data from \{file}");
    
    try (var scope = new ShutdownOnFailure()) {
      var importTask = scope.fork(() -> {
        Optional<Object> importedConfig = importObjectFromJson(file, EmailConfigurationData.class);
        
        // Use pattern matching to handle the imported configuration
        if (importedConfig.isPresent()) {
          switch (importedConfig.get()) {
            case EmailConfigurationData config -> {
              store.save(config);
              log.debug(STR."Successfully restored EmailConfiguration from \{file}");
            }
            case null -> log.warn(STR."Null configuration found in \{file}");
            default -> log.warn(STR."Unexpected configuration type: \{importedConfig.get().getClass().getName()}");
          }
        } else {
          log.debug(STR."No EmailConfiguration found in \{file}");
        }
        return true;
      });
      
      scope.join();           // Wait for the virtual thread to complete
      scope.throwIfFailed(); // Propagate any exceptions
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(STR."Restore operation was interrupted for \{file}", e);
    } catch (ExecutionException e) {
      throw new IOException(STR."Failed to restore EmailConfiguration from \{file}", e.getCause());
    }
  }
}