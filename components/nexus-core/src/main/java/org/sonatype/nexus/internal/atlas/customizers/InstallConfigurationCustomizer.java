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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.property.PropertiesFile;
import org.sonatype.nexus.supportzip.FileContentSourceSupport;
import org.sonatype.nexus.supportzip.SanitizedXmlSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import org.apache.commons.io.IOUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static org.sonatype.nexus.common.jdbc.JdbcUrlRedactor.redactPassword;
import static org.sonatype.nexus.supportzip.PasswordSanitizing.REPLACEMENT;
import static org.sonatype.nexus.supportzip.PasswordSanitizing.SENSITIVE_FIELD_NAMES;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.HIGH;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.CONFIG;

/**
 * Adds installation directory configuration files to support bundle.
 *
 * @since 3.0
 */
@Named
@Singleton
public class InstallConfigurationCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{
  private static final String INSTALL_ETC = "install/etc";

  private final ApplicationDirectories applicationDirectories;

  private final String NEXUS_PROPERTIES = "nexus.properties";

  @Inject
  public InstallConfigurationCustomizer(final ApplicationDirectories applicationDirectories) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
  }

  @Override
  public void customize(final SupportBundle supportBundle) {
    File installDir = applicationDirectories.getInstallDirectory();
    if (installDir != null) {
      File etcDir = new File(installDir, "etc");
      // Use String Templates for path construction
      includeFileIfExists(supportBundle, new File(etcDir, "nexus-default.properties"), INSTALL_ETC, HIGH);
      includeFileIfExists(supportBundle, new File(etcDir, NEXUS_PROPERTIES), INSTALL_ETC, HIGH);
      
      // Process all directories in parallel using Virtual Threads
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> tasks = new ArrayList<>();
        
        tasks.add(executor.submit(() -> 
            includeAllFilesInDirIfExists(supportBundle, new File(etcDir, "fabric"), INSTALL_ETC, HIGH)));
        tasks.add(executor.submit(() -> 
            includeAllFilesInDirIfExists(supportBundle, new File(etcDir, "jetty"), INSTALL_ETC, HIGH)));
        tasks.add(executor.submit(() -> 
            includeAllFilesInDirIfExists(supportBundle, new File(etcDir, "karaf"), INSTALL_ETC, HIGH)));
        tasks.add(executor.submit(() -> 
            includeAllFilesInDirIfExists(supportBundle, new File(etcDir, "logback"), INSTALL_ETC, HIGH)));
        
        // Wait for all tasks to complete
        for (Future<?> task : tasks) {
          try {
            task.get();
          } catch (Exception e) {
            log.error(STR."Error processing configuration directory: \{e.getMessage()}", e);
          }
        }
      }
    }

    File workDir = applicationDirectories.getWorkDirectory();
    if (workDir != null) {
      File etcDir = new File(workDir, "etc");
      includeFileIfExists(supportBundle, new File(etcDir, NEXUS_PROPERTIES), INSTALL_ETC, HIGH);
      
      // Process work directories in parallel using Virtual Threads
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> tasks = new ArrayList<>();
        
        tasks.add(executor.submit(() -> 
            includeAllFilesInDirIfExists(supportBundle, new File(etcDir, "fabric"), INSTALL_ETC, HIGH)));
        tasks.add(executor.submit(() -> 
            includeAllFilesInDirIfExists(supportBundle, new File(etcDir, "logback"), INSTALL_ETC, HIGH)));
        
        // Wait for all tasks to complete
        for (Future<?> task : tasks) {
          try {
            task.get();
          } catch (Exception e) {
            log.error(STR."Error processing work directory: \{e.getMessage()}", e);
          }
        }
      }
    }
  }

  private void includeFileIfExists(
      final SupportBundle supportBundle,
      final File file,
      final String prefixDir,
      final Priority priority)
  {
    if (file != null && file.isFile()) {
      // Use String Templates for logging
      log.debug(STR."Including file: \{file}");
      String fileName = file.getName();
      // Use String Templates for path construction
      String filePath = STR."\{prefixDir}/\{fileName}";
      try {
        // Use Pattern Matching for switch to improve sanitization logic
        supportBundle.add(switch (fileName) {
          case String fn when fn.equals("jetty-https.xml") -> 
              new SanitizedJettyFileSource(CONFIG, filePath, file, priority);
          case String fn when fn.endsWith("store.properties") -> 
              new SanitizedDataStoreFileSource(CONFIG, filePath, file, priority);
          case String fn when fn.equals(NEXUS_PROPERTIES) -> 
              new SanitizedNexusFileSource(CONFIG, filePath, file, priority);
          default -> 
              new FileContentSourceSupport(CONFIG, filePath, file, priority);
        });
      }
      catch (IOException e) {
        log.warn(STR."Failed to sanitize \{file}", e);
      }
    }
    else {
      log.warn(STR."Skipping: \{file}");
    }
  }

  private void includeAllFilesInDirIfExists(
      final SupportBundle supportBundle,
      final File directory,
      final String prefixDir,
      final Priority priority)
  {
    if (directory != null && directory.isDirectory()) {
      log.debug(STR."Including dir: \{directory}");
      File[] files = directory.listFiles();
      if (files != null && files.length > 0) {
        // Use String Templates for path construction
        String dirPath = STR."\{prefixDir}/\{directory.getName()}";
        
        // Process files in parallel using Virtual Threads for improved performance
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          List<Future<?>> tasks = new ArrayList<>();
          
          for (File file : files) {
            tasks.add(executor.submit(() -> {
              includeFileIfExists(supportBundle, file, dirPath, priority);
              return null;
            }));
          }
          
          // Wait for all file processing tasks to complete
          for (Future<?> task : tasks) {
            try {
              task.get();
            } catch (Exception e) {
              log.error(STR."Error processing file in directory \{directory}: \{e.getMessage()}", e);
            }
          }
        }
      }
    }
    else {
      log.warn(STR."Skipping: \{directory}");
    }
  }

  /**
   * Ad-hoc subclass that encapsulates the XSLT transformation of a Jetty configuration file, removing passwords.
   */
  protected static class SanitizedJettyFileSource
      extends SanitizedXmlSourceSupport
  {
    /**
     * Constructor.
     */
    public SanitizedJettyFileSource(final Type type, final String path, final File file, final Priority priority)
        throws IOException
    {
      // Use String Templates for resource path construction
      String stylesheetPath = "jetty-stylesheet.xml";
      String stylesheet = IOUtils.toString(
          checkNotNull(SanitizedJettyFileSource.class.getResourceAsStream(stylesheetPath), 
              STR."Resource not found: \{stylesheetPath}"),
          UTF_8);
      super(type, path, file, priority, stylesheet);
    }
  }

  /**
   * Removes JDBC credentials from *-store.properties, if present.
   */
  protected static class SanitizedDataStoreFileSource
      extends FileContentSourceSupport
  {
    public SanitizedDataStoreFileSource(final Type type, final String path, final File file, final Priority priority) {
      super(CONFIG, path, file, priority);
    }

    @Override
    public InputStream getContent() throws Exception {
      PropertiesFile dataStoreConfiguration = new PropertiesFile(file);
      dataStoreConfiguration.load();
      
      // Process properties with pattern matching for improved sanitization logic
      dataStoreConfiguration.forEach((k, v) -> {
        switch (k) {
          case String key when SENSITIVE_FIELD_NAMES.contains(key) -> 
              dataStoreConfiguration.replace(key, REPLACEMENT);
          case "jdbcUrl" -> 
              dataStoreConfiguration.put(k, redactPassword((String) v));
          default -> { /* No sanitization needed */ }
        }
      });
      
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      dataStoreConfiguration.store(outputStream, null);
      return new ByteArrayInputStream(outputStream.toByteArray());
    }
  }

  protected static class SanitizedNexusFileSource
    extends FileContentSourceSupport
  {
        public SanitizedNexusFileSource(final Type type, final String path, final File file, final Priority priority) {
          super(CONFIG, path, file, priority);
        }

        @Override
        public InputStream getContent() throws Exception {
          PropertiesFile dataStoreConfiguration = new PropertiesFile(file);
          dataStoreConfiguration.load();
          
          // Process properties with pattern matching for improved sanitization logic
          dataStoreConfiguration.forEach((k, v) -> {
            switch (k) {
              case String key when SENSITIVE_FIELD_NAMES.contains(key) -> 
                  dataStoreConfiguration.replace(key, REPLACEMENT);
              case "nexus.datastore.nexus.jdbcUrl" -> 
                  dataStoreConfiguration.put(k, redactPassword((String) v));
              default -> { /* No sanitization needed */ }
            }
          });
          
          ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
          dataStoreConfiguration.store(outputStream, null);
          return new ByteArrayInputStream(outputStream.toByteArray());
        }
  }
}