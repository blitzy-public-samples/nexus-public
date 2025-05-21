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
package org.sonatype.nexus.internal.log.overrides.datastore;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.common.log.LoggerOverridesReloadEvent;
import org.sonatype.nexus.internal.log.overrides.LogbackLoggerOverridesSupport;
import org.sonatype.nexus.internal.log.LoggerOverrides;

import com.google.common.collect.ImmutableMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.STORAGE;

/**
 * {@link LoggerOverrides} datastore implementation.
 */
@Named
@Singleton
@ManagedLifecycle(phase = STORAGE)
public class DatastoreLoggerOverrides
    extends LogbackLoggerOverridesSupport
    implements LoggerOverrides
{
  private final LoggingOverridesStore loggingLevelsStore;

  private final EventManager eventManager;

  private final Map<String, LoggerLevel> loggerLevels = new ConcurrentHashMap<>();

  private final ReentrantReadWriteLock loggerLevelsLock = new ReentrantReadWriteLock();

  @Inject
  public DatastoreLoggerOverrides(
      final ApplicationDirectories appDirectories,
      final LoggingOverridesStore loggingLevelsStore,
      final EventManager eventManager)
  {
    super(appDirectories);
    this.loggingLevelsStore = checkNotNull(loggingLevelsStore);
    this.eventManager = checkNotNull(eventManager);
  }

  @Override
  public void load() {
    loggerLevels.clear();
    if (logbackFileExists()) {
      // Use Virtual Thread for I/O operations to improve performance
      Thread.startVirtualThread(() -> {
        try {
          Map<String, LoggerLevel> fileOverrides = readFromFile();
          loggerLevels.putAll(fileOverrides);
          log.debug(STR."Successfully loaded \{fileOverrides.size()} logger overrides from file");
        }
        catch (RuntimeException e) {
          log.error(STR."Failed to load logger overrides from file: \{e.getMessage()}", e);
          throw e;
        }
        catch (Exception e) {
          log.error(STR."Failed to load logger overrides from file: \{e.getMessage()}", e);
          throw new RuntimeException(STR."Error loading logger overrides: \{e.getMessage()}", e);
        }
      });
    }
  }

  @Override
  protected void doStart() throws Exception {
    log.debug(STR."Load overrides from datastore");
    syncWithDBAndGet();

    // if override level is loaded from xml file but not presented in db - migrate it
    List<LoggingOverridesData> levelsMigrateToDb = loggerLevels.entrySet()
        .stream()
        .filter(entry -> !loggingLevelsStore.exists(entry.getKey()))
        .map(entry -> new LoggingOverridesData(entry.getKey(), entry.getValue().toString()))
        .collect(Collectors.toList());

    // Notify overrides were reloaded using Virtual Thread for asynchronous processing
    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        eventManager.post(new LoggerOverridesReloadEvent());
        log.debug(STR."Logger overrides reload event posted successfully");
      }
      catch (Exception e) {
        log.error(STR."Failed to post logger overrides reload event: \{e.getMessage()}", e);
      }
    });

    // Migrate levels to database
    if (!levelsMigrateToDb.isEmpty()) {
      log.debug(STR."Migrating \{levelsMigrateToDb.size()} logger overrides to database");
      levelsMigrateToDb.forEach(loggingLevelsStore::create);
    }
  }

  @Override
  public Map<String, LoggerLevel> syncWithDBAndGet() {
    loggerLevelsLock.writeLock().lock();
    try {
      Continuation<LoggingOverridesData> levels = loggingLevelsStore.readRecords();
      levels.forEach(data -> loggerLevels.put(data.getName(), LoggerLevel.valueOf(data.getLevel())));
      return loggerLevels;
    }
    catch (Exception e) {
      log.error(STR."Failed to sync logger overrides with database: \{e.getMessage()}", e);
      throw e;
    }
    finally {
      loggerLevelsLock.writeLock().unlock();
    }
  }

  @Override
  public void reset() {
    loggerLevels.clear();
    try {
      loggingLevelsStore.deleteAllRecords();
      log.debug(STR."All logger overrides have been reset");
    }
    catch (Exception e) {
      log.error(STR."Failed to reset logger overrides: \{e.getMessage()}", e);
      throw e;
    }
  }

  @Override
  public void set(final String name, final LoggerLevel level) {
    loggerLevels.put(name, level);

    LoggingOverridesData data = new LoggingOverridesData(name, level.toString());
    try {
      if (loggingLevelsStore.exists(name)) {
        loggingLevelsStore.update(data);
        log.debug(STR."Updated logger override for \{name} to \{level}");
      }
      else {
        loggingLevelsStore.create(data);
        log.debug(STR."Created new logger override for \{name} with level \{level}");
      }
    }
    catch (Exception e) {
      log.error(STR."Failed to set logger override for \{name}: \{e.getMessage()}", e);
      throw e;
    }
  }

  @Nullable
  @Override
  public LoggerLevel get(final String name) {
    return loggerLevels.get(name);
  }

  @Nullable
  @Override
  public LoggerLevel remove(final String name) {
    LoggerLevel removed = loggerLevels.remove(name);
    try {
      loggingLevelsStore.deleteByName(name);
      if (removed != null) {
        log.debug(STR."Removed logger override for \{name}");
      }
    }
    catch (Exception e) {
      log.error(STR."Failed to remove logger override for \{name}: \{e.getMessage()}", e);
      throw e;
    }
    return removed;
  }

  @Override
  public boolean contains(final String name) {
    return loggerLevels.containsKey(name);
  }

  @Override
  public Iterator<Entry<String, LoggerLevel>> iterator() {
    return ImmutableMap.copyOf(loggerLevels).entrySet().iterator();
  }

  @Override
  public void save() {
    // empty. method 'set' writes data to db directly
  }
}