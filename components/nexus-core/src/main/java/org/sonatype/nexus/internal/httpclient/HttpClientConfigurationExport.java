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
package org.sonatype.nexus.internal.httpclient;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.supportzip.ExportConfigData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

/**
 * Write/Read {@link HttpClientConfiguration} data to/from a JSON file.
 * Uses Java 21 features for improved performance and readability.
 *
 * @since 3.29
 */
@Named("httpClientConfigurationExport")
@Singleton
public class HttpClientConfigurationExport
    extends JsonExporter
    implements ExportConfigData, ImportData
{
  private final HttpClientConfigurationStore store;

  @Inject
  public HttpClientConfigurationExport(final HttpClientConfigurationStore store) {
    this.store = store;
  }

  @Override
  public void export(final File file) throws IOException {
    log.debug(STR."Export HttpClientConfiguration data to \{file}");
    
    // Use virtual thread for I/O operation to improve performance
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          HttpClientConfiguration configuration = store.load();
          exportObjectToJson(configuration, file);
          log.debug(STR."Successfully exported HttpClientConfiguration data to \{file}");
        } catch (IOException e) {
          log.error(STR."Error exporting HttpClientConfiguration data to \{file}: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).join(); // Wait for completion without checked exceptions
    }
  }

  @Override
  public void restore(final File file) throws IOException {
    log.debug(STR."Restoring HttpClientConfiguration data from \{file}");
    
    // Use virtual thread for I/O operation to improve performance
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          Optional<HttpClientConfigurationData> configuration = importObjectFromJson(file, HttpClientConfigurationData.class);
          if (configuration.isPresent()) {
            store.save(configuration.get());
            log.debug(STR."Successfully restored HttpClientConfiguration data from \{file}");
          } else {
            log.warn(STR."No valid HttpClientConfiguration data found in \{file}");
          }
        } catch (IOException e) {
          log.error(STR."Error restoring HttpClientConfiguration data from \{file}: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).join(); // Wait for completion without checked exceptions
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }
}