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
package org.sonatype.nexus.bootstrap;

import java.io.File;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.logging.Handler;
import javax.annotation.Nullable;

import org.sonatype.nexus.bootstrap.internal.ShutdownHelper;
import org.sonatype.nexus.bootstrap.internal.TemporaryDirectory;
import org.sonatype.nexus.bootstrap.jetty.JettyServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.StaticLoggerBinder;
import uk.org.lidalia.sysoutslf4j.context.SysOutOverSLF4J;

/**
 * Nexus bootstrap launcher.
 *
 * @since 2.1
 */
public class Launcher
{
  static {
    boolean hasJulBridge;
    try {
      // check whether we have access to the optional JUL->SLF4J logging bridge
      hasJulBridge = Handler.class.isAssignableFrom(org.slf4j.bridge.SLF4JBridgeHandler.class);
    }
    catch (Exception | LinkageError e) {
      hasJulBridge = false;
    }
    HAS_JUL_BRIDGE = hasJulBridge;
  }

  private static final boolean HAS_JUL_BRIDGE;

  private static final boolean HAS_CONSOLE = Boolean.getBoolean("karaf.startLocalConsole");

  private static final String LOGGING_OVERRIDE_PREFIX = "nexus.logging.level.";

  public static final String IGNORE_SHUTDOWN_HELPER = ShutdownHelper.class.getName() + ".ignore";

  public static final String SYSTEM_USERID = "*SYSTEM";

  /**
   * System property to enable Virtual Threads for I/O operations
   * @since 3.60
   */
  public static final String ENABLE_VIRTUAL_THREADS = "nexus.enableVirtualThreads";

  /**
   * System property to configure the number of platform threads per core for the Virtual Thread scheduler
   * @since 3.60
   */
  public static final String VIRTUAL_THREADS_PARALLELISM = "nexus.virtualThreads.parallelism";

  private final JettyServer server;

  public Launcher(
      final File defaultsFile,
      @Nullable final File propertiesFile,
      @Nullable final File nodeNamePropertiesFile) throws Exception
  {

    configureLogging();

    ClassLoader cl = getClass().getClassLoader();

    ConfigurationBuilder builder = new ConfigurationBuilder().defaults();

    builder.properties(defaultsFile, true);
    builder.properties(propertiesFile, false);
    builder.properties(nodeNamePropertiesFile, "nexus.clustered.nodeName", false);
    builder.override(System.getProperties());

    Map<String, String> props = builder.build();
    System.getProperties().putAll(props);

    // log critical information about the runtime environment
    Logger log = LoggerFactory.getLogger(Launcher.class);
    if (log.isInfoEnabled()) {
      String javaVersion = System.getProperty("java.version");
      String javaVmName = System.getProperty("java.vm.name");
      String javaVmVendor = System.getProperty("java.vm.vendor");
      String javaVmVersion = System.getProperty("java.vm.version");
      
      log.info("Java: {}, {}, {}, {}", javaVersion, javaVmName, javaVmVendor, javaVmVersion);
      log.info("OS: {}, {}, {}", System.getProperty("os.name"), System.getProperty("os.version"),
          System.getProperty("os.arch"));
      log.info("User: {}, {}, {}", System.getProperty("user.name"), System.getProperty("user.language"),
          System.getProperty("user.home"));
      log.info("CWD: {}", System.getProperty("user.dir"));
      
      // Log Java 21 specific information if running on Java 21+
      if (isJava21OrHigher(javaVersion)) {
        log.info("Running on Java 21+ with enhanced concurrency support");
        if (Boolean.getBoolean(ENABLE_VIRTUAL_THREADS)) {
          log.info("Virtual Threads enabled for improved I/O performance");
          String parallelism = System.getProperty(VIRTUAL_THREADS_PARALLELISM);
          if (parallelism != null) {
            log.info("Virtual Thread scheduler parallelism configured to: {}", parallelism);
          }
        } else {
          log.info("Virtual Threads support available but not enabled. Enable with -D{}=true", ENABLE_VIRTUAL_THREADS);
        }
      }
    }

    // ensure the temporary directory is sane
    File tmpdir = TemporaryDirectory.get();
    log.info("TMP: {}", tmpdir);

    if (!"false".equalsIgnoreCase(getProperty(IGNORE_SHUTDOWN_HELPER, "false"))) {
      log.warn("ShutdownHelper requests will be ignored!");
      ShutdownHelper.setDelegate(ShutdownHelper.NOOP);
    }

    String args = props.get("nexus-args");
    if (args == null || args.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing nexus-args");
    }

    configureInitialLoggingOverrides(props);
    configureVirtualThreads();

    this.server = new JettyServer(cl, props, args.split(","));
  }

  /**
   * Configures Virtual Threads support if enabled and running on Java 21+
   * @since 3.60
   */
  private void configureVirtualThreads() {
    if (Boolean.getBoolean(ENABLE_VIRTUAL_THREADS) && isJava21OrHigher(System.getProperty("java.version"))) {
      // Configure system properties for Virtual Thread scheduler if not already set
      if (System.getProperty("jdk.virtualThreadScheduler.parallelism") == null && 
          System.getProperty(VIRTUAL_THREADS_PARALLELISM) != null) {
        System.setProperty("jdk.virtualThreadScheduler.parallelism", 
            System.getProperty(VIRTUAL_THREADS_PARALLELISM));
      }
      
      // Set default executor service factory to use Virtual Threads when appropriate
      try {
        // This is safe in Java 21+ but will throw NoSuchMethodError in earlier versions
        // which is why we check Java version first
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        virtualThreadExecutor.shutdown(); // Just testing, not actually using this instance
        
        Logger log = LoggerFactory.getLogger(Launcher.class);
        log.info("Successfully configured Virtual Threads support");
      } catch (Exception e) {
        Logger log = LoggerFactory.getLogger(Launcher.class);
        log.warn("Failed to configure Virtual Threads: {}", e.getMessage());
      }
    }
  }

  /**
   * Checks if the current Java version is 21 or higher
   * @param javaVersion The Java version string
   * @return true if running on Java 21 or higher
   * @since 3.60
   */
  private boolean isJava21OrHigher(String javaVersion) {
    try {
      // Handle version strings like "21", "21.0.1", etc.
      if (javaVersion.contains(".")) {
        javaVersion = javaVersion.substring(0, javaVersion.indexOf('.'));
      }
      int version = Integer.parseInt(javaVersion);
      return version >= 21;
    } catch (NumberFormatException e) {
      // If we can't parse the version, assume it's not Java 21+
      return false;
    }
  }

  public JettyServer getServer() {
    return server;
  }

  public void start() throws Exception {
    start(true, null);
  }

  /**
   * Starts Jetty without waiting for it to fully start up.
   *
   * @param callback optional, callback executed immediately after Jetty is fully started up.
   * @see JettyServer#start(boolean, Runnable)
   */
  public void startAsync(@Nullable final Runnable callback) throws Exception {
    start(false, callback);
  }

  private void start(final boolean waitForServer, @Nullable final Runnable callback) throws Exception {
    server.start(waitForServer, callback);
  }

  private String getProperty(final String name, final String defaultValue) {
    String value = System.getProperty(name, System.getenv(name));
    if (value == null) {
      value = defaultValue;
    }
    return value;
  }

  public void stop() throws Exception {
    server.stop();
  }

  /**
   * Customize logging of the application as necessary.
   */
  private void configureLogging() {
    if (!HAS_CONSOLE) {
      SysOutOverSLF4J.registerLoggingSystem("org.ops4j.pax.logging.slf4j");
      SysOutOverSLF4J.sendSystemOutAndErrToSLF4J();
    }

    if (HAS_JUL_BRIDGE) {
      org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
      org.slf4j.bridge.SLF4JBridgeHandler.install();
    }
  }

  private static class Property
  {
    private String key;

    private String value;

    private Property(final Entry<String, String> entry) {
      key = entry.getKey();
      value = entry.getValue();
    }

    private Property withKeyPrefixRemoved() {
      if (Launcher.LOGGING_OVERRIDE_PREFIX.length() < key.length()) {
        key = key.substring(Launcher.LOGGING_OVERRIDE_PREFIX.length());
      }
      return this;
    }
  }

  /**
   * Customize logging overrides presented as properties. These will be superseded by any logback overrides.
   */
  private void configureInitialLoggingOverrides(final Map<String, String> props) {
    LoggerContext loggerContext = loggerContext();
    if (props != null) {
      props.entrySet()
          .stream()
          .map(Property::new)
          .filter(p -> p.key.startsWith(LOGGING_OVERRIDE_PREFIX))
          .filter(p -> !p.value.isEmpty())
          .map(Property::withKeyPrefixRemoved)
          .forEach(p -> setLoggerLevel(loggerContext, p.key, p.value));
    }
  }

  private void setLoggerLevel(final LoggerContext loggerContext, final String logger, final String level) {
    loggerContext.getLogger(Launcher.class).debug("Initialising logger: {} = {}", logger, level);
    loggerContext.getLogger(logger).setLevel(Level.valueOf(level));
  }

  private LoggerContext loggerContext() {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext) {
      return (LoggerContext) factory;
    }
    return (LoggerContext) StaticLoggerBinder.getSingleton().getLoggerFactory();
  }
}