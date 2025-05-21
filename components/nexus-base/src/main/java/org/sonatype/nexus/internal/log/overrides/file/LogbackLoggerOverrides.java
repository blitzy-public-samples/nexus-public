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
package org.sonatype.nexus.internal.log.overrides.file;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.internal.log.overrides.LogbackLoggerOverridesSupport;
import org.sonatype.nexus.internal.log.LoggerOverrides;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

/**
 * Logback {@link LoggerOverrides} implementation.
 *
 * Special handling for {@code ROOT} logger, which is persisted as {@code root.level} property.
 *
 * @since 2.7
 */
@Named
@Singleton
public class LogbackLoggerOverrides
    extends LogbackLoggerOverridesSupport
    implements LoggerOverrides
{
  private final Map<String, LoggerLevel> loggerLevels = new HashMap<>();
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  @Inject
  public LogbackLoggerOverrides(final ApplicationDirectories applicationDirectories) {
    super(applicationDirectories);
  }

  @VisibleForTesting
  LogbackLoggerOverrides(final File file) {
    super(file);
  }

  @Override
  public void load() {
    log.debug(STR."Load logger overrides");

    lock.writeLock().lock();
    try {
      loggerLevels.clear();
      if (logbackFileExists()) {
        try {
          loggerLevels.putAll(readFromFile());
        }
        catch (Exception e) {
          // Use pattern matching to handle specific exception types
          if (e instanceof IOException ioe) {
            throw new RuntimeException(STR."I/O error loading logger overrides: \{ioe.getMessage()}", ioe);
          }
          else if (e instanceof IllegalArgumentException iae) {
            throw new RuntimeException(STR."Invalid logger override configuration: \{iae.getMessage()}", iae);
          }
          else {
            throw new RuntimeException(STR."Failed to load logger overrides: \{e.getMessage()}", e);
          }
        }
      }
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void save() {
    log.debug(STR."Save logger overrides");

    lock.readLock().lock();
    try {
      writeToFile(loggerLevels);
    }
    catch (Exception e) {
      // Use pattern matching to handle specific exception types
      if (e instanceof IOException ioe) {
        throw new RuntimeException(STR."I/O error saving logger overrides: \{ioe.getMessage()}", ioe);
      }
      else {
        throw new RuntimeException(STR."Failed to save logger overrides: \{e.getMessage()}", e);
      }
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Map<String, LoggerLevel> syncWithDBAndGet() {
    // Not applicable to Orient
    lock.readLock().lock();
    try {
      return ImmutableMap.copyOf(loggerLevels);
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public void reset() {
    log.debug(STR."Reset logger overrides");

    lock.writeLock().lock();
    try {
      loggerLevels.clear();
      try {
        writeToFile(loggerLevels);
      }
      catch (Exception e) {
        // Use pattern matching to handle specific exception types
        if (e instanceof IOException ioe) {
          throw new RuntimeException(STR."I/O error resetting logger overrides: \{ioe.getMessage()}", ioe);
        }
        else {
          throw new RuntimeException(STR."Failed to reset logger overrides: \{e.getMessage()}", e);
        }
      }
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void set(final String name, final LoggerLevel level) {
    log.debug(STR."Set logger override: \{name}=\{level}");

    lock.writeLock().lock();
    try {
      loggerLevels.put(name, level);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  @Nullable
  public LoggerLevel get(final String name) {
    lock.readLock().lock();
    try {
      return loggerLevels.get(name);
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  @Nullable
  public LoggerLevel remove(final String name) {
    log.debug(STR."Remove logger override: \{name}");

    lock.writeLock().lock();
    try {
      return loggerLevels.remove(name);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public boolean contains(final String name) {
    lock.readLock().lock();
    try {
      return loggerLevels.containsKey(name);
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Iterator<Entry<String, LoggerLevel>> iterator() {
    lock.readLock().lock();
    try {
      return ImmutableMap.copyOf(loggerLevels).entrySet().iterator();
    }
    finally {
      lock.readLock().unlock();
    }
  }
}
