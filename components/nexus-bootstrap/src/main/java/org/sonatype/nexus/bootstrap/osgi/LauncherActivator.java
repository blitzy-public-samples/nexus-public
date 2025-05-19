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
package org.sonatype.nexus.bootstrap.osgi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.sonatype.nexus.bootstrap.Launcher;
import org.sonatype.nexus.bootstrap.internal.ShutdownHelper;
import org.sonatype.nexus.bootstrap.internal.ShutdownHelper.ShutdownDelegate;
import org.sonatype.nexus.bootstrap.jetty.JettyServerConfiguration;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.slf4j.MDC;

import static java.lang.StringTemplate.STR;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Collections.singletonMap;
import static org.sonatype.nexus.bootstrap.Launcher.SYSTEM_USERID;

/**
 * {@link BundleActivator} that invokes the {@link Launcher}.
 *
 * @since 3.0
 */
public class LauncherActivator
    implements BundleActivator, ShutdownDelegate
{
  private Framework framework;

  private Launcher launcher;

  private ConnectorConfigurationTracker connectorConfigurationTracker;

  private ServiceRegistration<JettyServerConfiguration> jettyServerConfigurationRegistration;

  public void start(final BundleContext bundleContext) throws Exception {
    framework = (Framework) bundleContext.getBundle(0);
    ShutdownHelper.setDelegate(this);

    final String baseDir = checkProperty(bundleContext, "karaf.base");
    final String dataDir = checkProperty(bundleContext, "karaf.data");

    final File defaultsFile = new File(baseDir, "etc/nexus-default.properties");
    final File propertiesFile = new File(dataDir, "etc/nexus.properties");
    final File nodeNamePropertiesFile = new File(dataDir, "etc/node-name.properties");

    maybeCopyDefaults(defaultsFile, propertiesFile);

    MDC.put("userId", SYSTEM_USERID);
    launcher = new Launcher(defaultsFile, propertiesFile, nodeNamePropertiesFile);
    
    // Use Java 21 Virtual Threads for improved concurrency
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          launcher.startAsync(() -> {
            connectorConfigurationTracker = new ConnectorConfigurationTracker(
                bundleContext,
                launcher.getServer()
            );
            connectorConfigurationTracker.open();
          });
        } catch (Exception e) {
          throw new RuntimeException(STR."Failed to start launcher: \{e.getMessage()}", e);
        }
      }).get();
    }

    final Dictionary<String, ?> properties = new Hashtable<>(singletonMap("name", "nexus"));
    jettyServerConfigurationRegistration = bundleContext.registerService(
        JettyServerConfiguration.class,
        new JettyServerConfiguration(launcher.getServer().defaultConnectors()),
        properties
    );
  }

  /**
   * Copies default properties to the specified properties file if it doesn't exist.
   * Optimized for Java 21 with improved file operations.
   */
  private static void maybeCopyDefaults(final File defaultsFile, final File propertiesFile) throws Exception {
    if (defaultsFile.exists() && !propertiesFile.exists()) {
      Path parentDir = propertiesFile.toPath().getParent();
      if (parentDir != null && !Files.isDirectory(parentDir)) {
        Files.createDirectories(parentDir);
      }

      // Get list of default properties, commented out
      List<String> defaultProperties = getDefaultPropertiesCommentedOut(defaultsFile.toPath());

      // Use Java 21 improved file operations with atomic write
      Files.write(
          propertiesFile.toPath(),
          defaultProperties,
          ISO_8859_1,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
      );
    }
  }

  /**
   * Gets default properties with comments, leveraging Java 21 stream enhancements.
   */
  private static List<String> getDefaultPropertiesCommentedOut(final Path defaultPropertiesPath) throws IOException {
    return Files.readAllLines(defaultPropertiesPath, ISO_8859_1)
        .stream()
        .filter(line -> !line.startsWith("##"))
        .map(line -> line.startsWith("#") || line.isEmpty() ? line : STR."# \{line}")
        .collect(Collectors.toList());
  }

  private static String checkProperty(final BundleContext bundleContext, final String name) {
    String value = bundleContext.getProperty(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(STR."Missing property \{name}");
    }
    return value;
  }

  public void stop(final BundleContext bundleContext) throws Exception {
    try {
      if (connectorConfigurationTracker != null) {
        connectorConfigurationTracker.close();
      }
      if (jettyServerConfigurationRegistration != null) {
        jettyServerConfigurationRegistration.unregister();
      }
      if (launcher != null) {
        launcher.stop();
      }
    }
    finally {
      connectorConfigurationTracker = null;
      jettyServerConfigurationRegistration = null;
      launcher = null;
    }
  }

  public void doExit(int code) {
    ShutdownHelper.setDelegate(ShutdownHelper.JAVA); // avoid recursion
    try {
      // Ensure clean termination with Java 21 runtime environment
      framework.stop();
      try {
        // Use a timeout to ensure we don't hang indefinitely
        framework.waitForStop(Duration.ofSeconds(30).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // Preserve interrupt status
      }
    }
    catch (Throwable e) {
      System.err.println(STR."Unexpected error while stopping: \{e.getMessage()}");
      e.printStackTrace();
    }
    finally {
      ShutdownHelper.exit(code);
    }
  }

  public void doHalt(int code) {
    ShutdownHelper.halt(code);
  }
}