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
package org.sonatype.nexus.karaf;

import java.io.File;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.Version;

/**
 * Nexus alternative to Karaf's main launcher which checks the Java version before launching.
 * 
 * @since 3.0
 */
public class NexusMain
    extends org.apache.karaf.main.Main
{
  // Visible for testing
  static final Version MINIMUM_JAVA_VERSION = new Version(21, 0, 0);

  Logger log = Logger.getLogger(this.getClass().getName());

  public NexusMain(final String[] args) {
    super(args);
  }

  /**
   * Adapted from {@link org.apache.karaf.main.Main#main(String[])} to call our constructor.
   */
  public static void main(final String[] args) throws Exception {
    System.setProperty("java.util.logging.manager", "org.sonatype.nexus.karaf.NonResettableLogManager");
    while (true) {
      boolean restart = false;
      boolean restartJvm = false;
      // karaf.restart.jvm take priority over karaf.restart
      System.setProperty("karaf.restart.jvm", "false");
      System.setProperty("karaf.restart", "false");
      final NexusMain main = new NexusMain(args);
      try {
        main.launch();
      }
      catch (Throwable ex) {
        // Also log to sytem.err in case logging is not yet initialized
        System.err.println(ex.getMessage());

        main.log.log(Level.SEVERE, "Could not launch framework", ex);
        main.destroy();
        main.setExitCode(-1);
      }
      try {
        main.awaitShutdown();
        boolean stopped = main.destroy();
        restart = Boolean.getBoolean("karaf.restart");
        restartJvm = Boolean.getBoolean("karaf.restart.jvm");
        main.updateInstancePidAfterShutdown();
        if (!stopped) {
          if (restart) {
            System.err.println("Timeout waiting for framework to stop.  Restarting now.");
          }
          else {
            System.err.println("Timeout waiting for framework to stop.  Exiting VM.");
            main.setExitCode(-3);
          }
        }
      }
      catch (Throwable ex) {
        main.setExitCode(-2);
        System.err.println("Error occurred shutting down framework: " + ex);
        ex.printStackTrace();
      }
      finally {
        if (restartJvm && restart) {
          System.exit(10);
        }
        else if (!restart) {

          int overriddenExitCode = Integer.getInteger("nexus.overrideExitCode", 0);
          if (overriddenExitCode != 0) {
            System.err.println("Exited with code: " + overriddenExitCode);
            System.err.println("Please check the previous log messages to identify why shutdown was initiated.");
            System.exit(overriddenExitCode);
          }
          System.exit(main.getExitCode());
        }
        else {
          System.gc();
        }
      }
    }
  }

  /**
   * Launch method is called by static main as well as Pax-Exam via reflection.
   */
  @Override
  public void launch() throws Exception {
    requireMinimumJavaVersion();

    File baseDir = getDirectory("karaf.base");
    File dataDir = getDirectory("karaf.data");

    // default properties required immediately at launch
    setDirectory("karaf.instances", dataDir, "instances");
    setDirectory("karaf.etc", baseDir, "etc/karaf");
    setDirectory("logback.etc", baseDir, "etc/logback");

    log.info("Launching Nexus..."); // temporary logging just to show custom launcher is being used in ITs
    super.launch();
    log.info("...launched Nexus!");
  }

  private IllegalArgumentException badArgument(final String format, final Object... args) {
    String message = String.format(format, args);
    Object cause = args.length > 0 ? args[args.length - 1] : null;
    if (cause instanceof Throwable) {
      log.log(Level.SEVERE, message, (Throwable) cause);
      return new IllegalArgumentException(message, (Throwable) cause);
    }
    log.log(Level.SEVERE, message);
    return new IllegalArgumentException(message);
  }

  private File getDirectory(final String propertyName) {
    String path = System.getProperty(propertyName);
    if (path == null || path.trim().isEmpty()) {
      throw badArgument("Missing property %s", propertyName);
    }
    try {
      File directory = new File(path).getCanonicalFile();
      if (!directory.isDirectory()) {
        Files.createDirectories(directory.toPath());
      }
      return directory;
    }
    catch (Exception e) {
      throw badArgument("No such directory %s (%s)", path, propertyName, e);
    }
  }

  private static void setDirectory(final String propertyName, final File parent, final String child) {
    System.setProperty(propertyName, new File(parent, child).getAbsolutePath());
  }

  // Visible for testing
  static void requireMinimumJavaVersion() {
    if (isNotSupportedVersion(System.getProperty("java.version"))) {
      // logging is not configured yet, so use console
      System.err.println("Nexus requires minimum Java version: " + MINIMUM_JAVA_VERSION + " (Java 21)");
      if (versionCheckRequired()) {
        System.exit(-1);
      }
    }
  }

  private static boolean isNotSupportedVersion(final String currentVersion) {
    try {
      // Handle Java 21's version format which can be "21", "21.0.1", etc.
      Version parsedVersion;
      if (currentVersion.matches("^\\d+$")) {
        // Simple version like "21"
        parsedVersion = new Version(Integer.parseInt(currentVersion), 0, 0);
      } else if (currentVersion.matches("^\\d+\\.\\d+\\.\\d+.*$")) {
        // Version like "21.0.1" or with additional information
        String[] parts = currentVersion.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int micro = Integer.parseInt(parts[2].replaceAll("[^0-9].*", ""));
        parsedVersion = new Version(major, minor, micro);
      } else {
        // Legacy format like "1.8.0_301"
        parsedVersion = new Version(currentVersion.replace('_', '.'));
      }
      return MINIMUM_JAVA_VERSION.compareTo(parsedVersion) > 0;
    }
    catch (IllegalArgumentException e) {
      System.err.println("Unable to parse Java version: " + currentVersion + ". Java 21 or later is required.");
      return true;
    }
  }

  private static boolean versionCheckRequired() {
    return Boolean.parseBoolean(System.getProperty("nexus.vmCheck", "true"));
  }
}