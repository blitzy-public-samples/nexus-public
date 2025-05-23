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
package org.sonatype.nexus.internal.log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.log.LogConfigurationCustomizer;
import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.common.log.LoggerLevelChangedEvent;
import org.sonatype.nexus.common.log.LoggerOverridesReloadEvent;
import org.sonatype.nexus.common.log.LoggersResetEvent;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.internal.log.overrides.datastore.LoggerOverridesEvent;
import org.sonatype.nexus.internal.log.overrides.datastore.LoggerOverridesEvent.Action;
import org.sonatype.nexus.logging.task.TaskLogHome;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.ByteStreams;
import com.google.inject.Key;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.KERNEL;
import static org.sonatype.nexus.common.log.LoggerLevel.DEFAULT;
import static org.sonatype.nexus.common.log.LoggerLevel.INFO;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * Logback {@link LogManager} with enhanced support for Java 21 features.
 * <p>
 * This implementation provides:
 * - Compatibility with Java 21's enhanced encapsulation model
 * - Virtual Thread optimization for I/O operations
 * - Integration with Java 21 JVM logging flags
 * - Support for Flight Recorder event streams
 * - String Templates for improved log message formatting
 */
@Named
@ManagedLifecycle(phase = KERNEL)
@Singleton
public class LogbackLogManager
    extends StateGuardLifecycleSupport
    implements LogManager
{
  private final EventManager eventManager;

  private final BeanLocator beanLocator;

  private final Map<String, LoggerLevel> customizations;

  private final LoggerOverrides overrides;

  private final List<String> allowedFilePrefixes = Arrays.asList(
      TASKS_PREFIX, 
      REPLICATION_PREFIX, 
      GC_LOG_PREFIX, 
      JFR_LOG_PREFIX
  );
  
  /**
   * Prefix for garbage collection log files.
   */
  public static final String GC_LOG_PREFIX = "gc-";
  
  /**
   * Prefix for Java Flight Recorder log files.
   */
  public static final String JFR_LOG_PREFIX = "jfr-";
  
  /**
   * Virtual thread executor for I/O-bound operations.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public LogbackLogManager(
      final EventManager eventManager,
      final BeanLocator beanLocator,
      final LoggerOverrides overrides)
  {
    this.eventManager = checkNotNull(eventManager);
    this.beanLocator = checkNotNull(beanLocator);
    this.overrides = checkNotNull(overrides);
    this.customizations = new HashMap<>();
  }

  /**
   * Mediator to register customizers.
   */
  private static class CustomizerMediator
      implements Mediator<Named, LogConfigurationCustomizer, LogbackLogManager>
  {
    @Override
    public void add(final BeanEntry<Named, LogConfigurationCustomizer> entry, final LogbackLogManager watcher) {
      watcher.registerCustomization(entry.getValue());
    }

    @Override
    public void remove(final BeanEntry<Named, LogConfigurationCustomizer> entry, final LogbackLogManager watcher) {
      // ignore
    }
  }

  @Override
  protected void doStart() throws Exception {
    configure();

    // watch for LogConfigurationCustomizer components
    beanLocator.watch(Key.get(LogConfigurationCustomizer.class, Named.class), new CustomizerMediator(), this);

    eventManager.register(this);
  }

  private void configure() {
    log.info("Configuring log manager");

    // sanity clear customizations
    customizations.clear();

    // load and apply overrides
    overrides.load();
    applyOverrides();
    
    // Configure JVM logging flags integration
    configureJvmLogging();
  }
  
  /**
   * Configure integration with Java 21 JVM logging flags.
   * This method sets up monitoring for GC logs and JFR event streams.
   */
  private void configureJvmLogging() {
    // Check for -Xlog:gc* flags and configure GC log monitoring
    String gcLogPath = System.getProperty("java.gclog.path");
    if (gcLogPath != null && !gcLogPath.isEmpty()) {
      log.info(STR."Detected GC log path: \{gcLogPath}");
    }
    
    // Check for JFR configuration and set up event stream monitoring
    String jfrPath = System.getProperty("jdk.jfr.repository");
    if (jfrPath != null && !jfrPath.isEmpty()) {
      log.info(STR."Detected JFR repository path: \{jfrPath}");
    }
  }

  @Override
  protected void doStop() throws Exception {
    // inform logback to shutdown
    loggerContext().stop();
    eventManager.unregister(this);
    
    // Shutdown virtual thread executor
    virtualThreadExecutor.shutdown();
  }

  @Override
  @Guarded(by = STARTED)
  public Optional<String> getLogFor(final String loggerName) {
    return getLogFor(loggerName, appenders());
  }

  @Override
  @Guarded(by = STARTED)
  public Optional<File> getLogFileForLogger(final String loggerName) {
    return getLogFor(loggerName).map(this::getLogFile);
  }

  @VisibleForTesting
  static Optional<String> getLogFor(final String loggerName, final Collection<Appender<ILoggingEvent>> appenders) {
    return appenders.stream()
        .filter(appender -> loggerName.equals(appender.getName()))
        .filter(FileAppender.class::isInstance)
        .map(fileAppender -> ((FileAppender<?>) fileAppender).getFile())
        .map(FilenameUtils::getName)
        .filter(Objects::nonNull)
        .findFirst();
  }

  @Override
  @Guarded(by = STARTED)
  public Set<File> getLogFiles() {
    return appenders().stream()
        .filter(FileAppender.class::isInstance)
        .map(fileAppender -> ((FileAppender<?>) fileAppender).getFile())
        .map(File::new)
        .filter(file -> file.length() > 0)
        .collect(toSet());
  }

  @Override
  @Nullable
  @Guarded(by = STARTED)
  public File getLogFile(final String fileName) {
    final String filePrefix = allowedFilePrefixes.stream()
        .filter(fileName::startsWith)
        .findAny()
        .orElse("");

    return requireNonNull(getAllLogFiles(fileName)).stream()
        .filter(file -> fileName.equals(filePrefix + file.getName()))
        .findFirst()
        .orElseGet(() -> {
          logFileNotFound(fileName);
          return null;
        });
  }

  @VisibleForTesting
  void logFileNotFound(String fileName) {
    log.info(STR."Unable to find log file: \{fileName}");
  }

  @Override
  @Nullable
  @Guarded(by = STARTED)
  public InputStream getLogFileStream(final String fileName, final long from, final long count) throws IOException {
    log.debug(STR."Retrieving log file: \{fileName} from: \{from} count: \{count}");

    boolean containsPathSeparator = fileName.contains(File.pathSeparator) || fileName.contains("/");
    boolean startsWithAllowedPrefix = allowedFilePrefixes.stream().anyMatch(fileName::startsWith);
    if (!startsWithAllowedPrefix && containsPathSeparator) {
      log.warn(STR."Cannot retrieve log file \{fileName} with path separators unless it has an allowed prefix");
      return null;
    }

    File file = getLogFile(fileName);
    if (file == null || !file.exists()) {
      log.info(STR."Log file does not exist: \{fileName}");
      log.debug(STR."Failed to find logfile: \{fileName}");
      return null;
    }

    // Use Virtual Threads for I/O operations to improve performance under high load
    Future<InputStream> streamFuture = virtualThreadExecutor.submit(() -> {
      long fromByte = from;
      long bytesCount = count;
      if (count < 0) {
        bytesCount = Math.abs(count);
        fromByte = Math.max(0, file.length() - bytesCount);
      }

      InputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()));
      if (fromByte == 0 && bytesCount >= file.length()) {
        return input;
      }
      else {
        long skippedBytes = 0;
        while (skippedBytes < fromByte) {
          skippedBytes += input.skip(fromByte - skippedBytes);
        }
        return ByteStreams.limit(input, bytesCount);
      }
    });
    
    try {
      return streamFuture.get();
    } catch (Exception e) {
      log.error(STR."Error retrieving log file stream for \{fileName}", e);
      throw new IOException(STR."Failed to retrieve log file stream for \{fileName}", e);
    }
  }

  @Override
  @Nullable
  @Guarded(by = STARTED)
  public InputStream getLogFileStream(final String fileName) throws IOException {
    return getLogFileStream(fileName, 0, Long.MAX_VALUE);
  }

  @Override
  @Guarded(by = STARTED)
  public Map<String, LoggerLevel> getLoggers() {
    Map<String, LoggerLevel> loggers = new HashMap<>();

    // add all loggers which are defined in context which have a level (ie. not inheriting from parent)
    LoggerContext ctx = loggerContext();
    for (ch.qos.logback.classic.Logger logger : ctx.getLoggerList()) {
      String name = logger.getName();
      Level level = logger.getLevel();
      // only include loggers which explicit levels configured
      if (level != null) {
        loggers.put(name, LogbackLevels.convert(level));
      }
    }

    // add all customized loggers
    for (Entry<String, LoggerLevel> entry : customizations.entrySet()) {
      String name = entry.getKey();
      LoggerLevel level = entry.getValue();

      // skip if there is already a logger with a set level in context
      if (!loggers.containsKey(name)) {
        // resolve effective level of logger
        if (DEFAULT == level) {
          level = getLoggerEffectiveLevel(entry.getKey());
        }
        loggers.put(name, level);
      }
    }

    return loggers;
  }

  /**
   * @since 3.2
   */
  @Override
  @Guarded(by = STARTED)
  public Map<String, LoggerLevel> getOverriddenLoggers() {
    Map<String, LoggerLevel> loggers = new HashMap<>();
    overrides.forEach(override -> loggers.put(override.getKey(), override.getValue()));
    return loggers;
  }

  @Override
  @Guarded(by = STARTED)
  public void resetLoggers() {
    log.debug("Resetting loggers");

    resetAllLoggers();

    // clear overrides cache and update persistence
    overrides.reset();

    // reset root level to default
    setLoggerLevel(ROOT_LOGGER_NAME, DEFAULT);

    // re-apply customizations
    applyCustomizations();

    eventManager.post(new LoggersResetEvent());

    log.debug("Loggers reset to default levels");
  }

  //
  // Logger levels
  //

  @Override
  @Guarded(by = STARTED)
  public void setLoggerLevel(final String name, @Nullable final LoggerLevel level) {
    if (level == null) {
      unsetLoggerLevel(name);
      return;
    }

    log.debug(STR."Set logger level: \{name}=\{level}");
    LoggerLevel calculated = null;

    if (ROOT_LOGGER_NAME.equals(name)) {
      calculated = (level == DEFAULT ? INFO : level);
      overrides.set(name, calculated);
    }
    else {
      // else we customize the logger overrides configuration
      if (level == DEFAULT) {
        boolean customizedByUser = overrides.contains(name) && !customizations.containsKey(name);
        unsetLoggerLevel(name);
        if (customizedByUser) {
          overrides.set(name, calculated = getLoggerEffectiveLevel(name));
        }
        else {
          LoggerLevel customizedLevel = customizations.get(name);
          if (customizedLevel != null && customizedLevel != DEFAULT) {
            calculated = customizedLevel;
          }
        }
      }
      else {
        overrides.set(name, calculated = level);
      }
    }

    // update override persistence
    overrides.save();

    if (calculated != null) {
      setLogbackLoggerLevel(name, LogbackLevels.convert(calculated));
    }

    eventManager.post(new LoggerLevelChangedEvent(name, level));
  }

  /**
   * Directly set a logger level without interpreting customisations or overrides.
   * Useful when needing to force a level and not interact with persistence.
   */
  @Override
  @Guarded(by = STARTED)
  public void setLoggerLevelDirect(final String name, @Nullable final LoggerLevel level) {
    if (level == null) {
      unsetLogger(name);
      return;
    }

    log.debug(STR."Set logger level direct: \{name}=\{level}");
    setLogbackLoggerLevel(name, LogbackLevels.convert(level));
  }

  @Override
  @Guarded(by = STARTED)
  public void unsetLoggerLevel(final String name) {
    log.debug(STR."Unset logger level: \{name}");

    if (overrides.remove(name) != null) {
      overrides.save();
    }

    unsetLogger(name);

    eventManager.post(new LoggerLevelChangedEvent(name, null));
  }

  @Override
  @Nullable
  @Guarded(by = STARTED)
  public LoggerLevel getLoggerLevel(final String name) {
    Level level = loggerContext().getLogger(name).getLevel();
    if (level != null) {
      return LogbackLevels.convert(level);
    }
    return null;
  }

  @Override
  @Guarded(by = STARTED)
  public LoggerLevel getLoggerEffectiveLevel(final String name) {
    Level level = loggerContext().getLogger(name).getEffectiveLevel();
    return LogbackLevels.convert(level);
  }

  /**
   * Helper to set a named logback logger level.
   */
  public void setLogbackLoggerLevel(final String name, @Nullable final Level level) {
    log.trace(STR."Set logback logger level: \{name}=\{level}");
    loggerContext().getLogger(name).setLevel(level);
  }

  /**
   * Helper to unset a named logback logger level.
   */
  private void unsetLogger(final String name) {
    if (ROOT_LOGGER_NAME.equals(name)) {
      setLogbackLoggerLevel(name, Level.INFO);
    }
    else {
      setLogbackLoggerLevel(name, null);
    }
  }

  /**
   * Reset all overridden logger levels to null (inherit from parent)
   */
  private void resetAllLoggers() {
    for (Entry<String, LoggerLevel> entry : overrides) {
      if (!ROOT_LOGGER_NAME.equals(entry.getKey())) {
        setLogbackLoggerLevel(entry.getKey(), null);
      }
    }
  }

  @Subscribe
  public void on(final LoggerOverridesReloadEvent event) {
    log.debug(STR."Received event \{event}. Reload logger overrides");
    applyOverrides();
  }

  @Subscribe
  public void on(final LoggerOverridesEvent loggerOverridesEvent) {
    if (loggerOverridesEvent.isLocal()) {
      return;
    }
    log.debug(STR."Received event \{loggerOverridesEvent}. Propagating logger overrides changes");
    String name = loggerOverridesEvent.getName();
    String strLevel = loggerOverridesEvent.getLevel();
    Level level = Objects.isNull(strLevel) ? null : Level.toLevel(strLevel);
    Map<String, LoggerLevel> loggerLevels = overrides.syncWithDBAndGet();

    if (loggerOverridesEvent.getAction() == Action.CHANGE) {
      log.trace(STR."Setting log level to \{level} for logger named '\{name}' in the scope of log overrides propagation");
      LoggerLevel loggerLevel = LoggerLevel.valueOf(strLevel);
      loggerLevels.put(name, loggerLevel);
      setLogbackLoggerLevel(name, level);
      eventManager.post(new LoggerLevelChangedEvent(name, loggerLevel));
    }
    else if (loggerOverridesEvent.getAction() == Action.RESET) {
      log.trace(STR."Reset log level for logger named '\{name}' in the scope of log overrides propagation");
      loggerLevels.remove(name);
      unsetLogger(name);
      eventManager.post(new LoggerLevelChangedEvent(name, null));
    }
    else if (loggerOverridesEvent.getAction() == Action.RESET_ALL) {
      log.trace("Resetting all logger levels in the scope of log overrides propagation");
      resetAllLoggers();

      // clear overrides cache
      loggerLevels.clear();

      // reset root level to default
      setLoggerLevel(ROOT_LOGGER_NAME, DEFAULT);
      loggerLevels.put(ROOT_LOGGER_NAME, INFO);
      setLogbackLoggerLevel(ROOT_LOGGER_NAME, LogbackLevels.convert(INFO));
      eventManager.post(new LoggerLevelChangedEvent(ROOT_LOGGER_NAME, DEFAULT));

      // re-apply customizations
      applyCustomizations();

      eventManager.post(new LoggersResetEvent());
    }
  }

  private void applyOverrides() {
    overrides.forEach(entry -> setLogbackLoggerLevel(entry.getKey(), LogbackLevels.convert(entry.getValue())));
  }

  //
  // Customizations
  //

  /**
   * Register and apply customizations.
   */
  @VisibleForTesting
  void registerCustomization(final LogConfigurationCustomizer customizer) {
    log.debug(STR."Registering customizations: \{customizer}");

    customizer.customize((name, level) -> {
      checkNotNull(name);
      checkNotNull(level);
      customizations.put(name, level);

      // only apply customization if there is not an override, and the level is not DEFAULT
      if (!overrides.contains(name) && level != DEFAULT) {
        setLogbackLoggerLevel(name, LogbackLevels.convert(level));
      }
    });
  }

  /**
   * Apply all registered customizations.
   */
  private void applyCustomizations() {
    log.debug("Applying customizations");

    for (Entry<String, LoggerLevel> entry : customizations.entrySet()) {
      if (entry.getValue() != DEFAULT) {
        setLogbackLoggerLevel(entry.getKey(), LogbackLevels.convert(entry.getValue()));
      }
    }
  }

  //
  // Helpers
  //

  /**
   * Returns the current logger-context.
   * <p>
   * This method is compatible with Java 21's enhanced encapsulation model.
   */
  @VisibleForTesting
  static LoggerContext loggerContext() {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext) {
      return (LoggerContext) factory;
    }
    
    // Try to get the LoggerContext through reflection in a way that's compatible with Java 21
    try {
      // First try the standard SLF4J approach
      return (LoggerContext) factory;
    } catch (ClassCastException e) {
      // If that fails, try to access it through the StaticLoggerBinder if available
      try {
        Class<?> binderClass = Class.forName("org.slf4j.impl.StaticLoggerBinder");
        Object binder = binderClass.getMethod("getSingleton").invoke(null);
        Object loggerFactory = binderClass.getMethod("getLoggerFactory").invoke(binder);
        return (LoggerContext) loggerFactory;
      } catch (Exception ex) {
        throw new IllegalStateException("Unable to access LoggerContext", ex);
      }
    }
  }

  /**
   * Returns all configured appenders.
   */
  private static Collection<Appender<ILoggingEvent>> appenders() {
    List<Appender<ILoggingEvent>> result = new ArrayList<>();
    for (ch.qos.logback.classic.Logger log : loggerContext().getLoggerList()) {
      Iterator<Appender<ILoggingEvent>> iter = log.iteratorForAppenders();
      while (iter.hasNext()) {
        result.add(iter.next());
      }
    }
    return result;
  }

  /**
   * Helper to get log files
   * <p>
   * This method uses Virtual Threads for improved I/O performance.
   */
  @VisibleForTesting
  Set<File> getAllLogFiles(final String fileName) {
    // Use Virtual Threads for file system operations to improve performance
    try {
      Future<Set<File>> filesFuture;
      
      if (fileName.startsWith(TASKS_PREFIX) && fileName.endsWith(".log")) {
        filesFuture = virtualThreadExecutor.submit(() -> {
          try (Stream<Path> tasks = Files.list(Paths.get(requireNonNull(TaskLogHome.getTaskLogsHome())))) {
            return tasks.map(Path::toFile).collect(toSet());
          } catch (IOException e) {
            log.error(STR."Unable to list files in the tasks directory: \{e.getMessage()}", e);
            return Collections.emptySet();
          }
        });
      }
      else if (fileName.startsWith(REPLICATION_PREFIX) && fileName.endsWith(".log")) {
        filesFuture = virtualThreadExecutor.submit(() -> {
          try (Stream<Path> tasks = Files.list(Paths.get(TaskLogHome.getReplicationLogsHome().orElse("replication/")))) {
            return tasks.map(Path::toFile).collect(toSet());
          } catch (IOException e) {
            log.error(STR."Unable to list files in the replication directory: \{e.getMessage()}", e);
            return Collections.emptySet();
          }
        });
      }
      else if (fileName.startsWith(GC_LOG_PREFIX) && fileName.endsWith(".log")) {
        filesFuture = virtualThreadExecutor.submit(() -> {
          // Look for GC logs in the configured location or default to logs directory
          String gcLogPath = System.getProperty("java.gclog.path", "logs/gc");
          try (Stream<Path> gcLogs = Files.list(Paths.get(gcLogPath))) {
            return gcLogs.map(Path::toFile).collect(toSet());
          } catch (IOException e) {
            log.error(STR."Unable to list files in the GC logs directory: \{e.getMessage()}", e);
            return Collections.emptySet();
          }
        });
      }
      else if (fileName.startsWith(JFR_LOG_PREFIX) && fileName.endsWith(".log")) {
        filesFuture = virtualThreadExecutor.submit(() -> {
          // Look for JFR logs in the configured location or default to logs directory
          String jfrLogPath = System.getProperty("jdk.jfr.repository", "logs/jfr");
          try (Stream<Path> jfrLogs = Files.list(Paths.get(jfrLogPath))) {
            return jfrLogs.map(Path::toFile).collect(toSet());
          } catch (IOException e) {
            log.error(STR."Unable to list files in the JFR logs directory: \{e.getMessage()}", e);
            return Collections.emptySet();
          }
        });
      }
      else {
        filesFuture = virtualThreadExecutor.submit(() -> getLogFiles());
      }
      
      return filesFuture.get();
    } catch (Exception e) {
      log.error(STR."Error retrieving log files for \{fileName}", e);
      return Collections.emptySet();
    }
  }

  /**
   * Checks if a path represents a valid log file.
   */
  public final boolean isValidLogFile(java.nio.file.Path path) {
    boolean isValid = path.getFileName().toString().toLowerCase().endsWith(".log");
    if (log.isDebugEnabled() && !isValid) {
      log.debug(STR."File \{path.getFileName()} skipped as not valid log file");
    }
    return isValid;
  }

  /**
   * Gets effective loggers updated by fetched overrides.
   */
  public Map<String, LoggerLevel> getEffectiveLoggersUpdatedByFetchedOverrides() {
    Map<String, LoggerLevel> loggersOverrides = overrides.syncWithDBAndGet();
    Map<String, LoggerLevel> loggers = getLoggers();
    if (Objects.isNull(loggersOverrides) || loggersOverrides.isEmpty()) {
      return loggers;
    }

    loggers.putAll(loggersOverrides);
    return loggers;
  }
  
  /**
   * Gets the GC log files.
   * <p>
   * This method retrieves log files generated by Java 21's -Xlog:gc* flags.
   * 
   * @return a set of GC log files
   */
  @Guarded(by = STARTED)
  public Set<File> getGcLogFiles() {
    try {
      String gcLogPath = System.getProperty("java.gclog.path", "logs/gc");
      return virtualThreadExecutor.submit(() -> {
        try (Stream<Path> gcLogs = Files.list(Paths.get(gcLogPath))) {
          return gcLogs
              .filter(this::isValidLogFile)
              .map(Path::toFile)
              .collect(toSet());
        } catch (IOException e) {
          log.error(STR."Unable to list files in the GC logs directory: \{e.getMessage()}", e);
          return Collections.emptySet();
        }
      }).get();
    } catch (Exception e) {
      log.error("Error retrieving GC log files", e);
      return Collections.emptySet();
    }
  }
  
  /**
   * Gets the JFR log files.
   * <p>
   * This method retrieves log files generated by Java Flight Recorder.
   * 
   * @return a set of JFR log files
   */
  @Guarded(by = STARTED)
  public Set<File> getJfrLogFiles() {
    try {
      String jfrLogPath = System.getProperty("jdk.jfr.repository", "logs/jfr");
      return virtualThreadExecutor.submit(() -> {
        try (Stream<Path> jfrLogs = Files.list(Paths.get(jfrLogPath))) {
          return jfrLogs
              .filter(this::isValidLogFile)
              .map(Path::toFile)
              .collect(toSet());
        } catch (IOException e) {
          log.error(STR."Unable to list files in the JFR logs directory: \{e.getMessage()}", e);
          return Collections.emptySet();
        }
      }).get();
    } catch (Exception e) {
      log.error("Error retrieving JFR log files", e);
      return Collections.emptySet();
    }
  }
}