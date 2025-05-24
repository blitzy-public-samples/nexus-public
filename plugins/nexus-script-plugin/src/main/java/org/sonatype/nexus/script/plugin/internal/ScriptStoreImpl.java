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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.transaction.Transactional;

import com.google.common.collect.ImmutableList;

/**
 * MyBatis {@link ScriptStore} implementation with Java 21 Virtual Thread support.
 * <p>
 * This implementation leverages Java 21 Virtual Threads for database operations to improve
 * I/O performance. The ScriptDAO interface is annotated with @VirtualThreadSupport, indicating
 * that it's optimized for execution in Virtual Threads.
 *
 * @since 3.21
 */
@Named("mybatis")
@Singleton
public class ScriptStoreImpl
    extends ConfigStoreSupport<ScriptDAO>
    implements ScriptStore
{
  /**
   * Virtual Thread executor for database operations.
   * <p>
   * This executor creates a new Virtual Thread for each submitted task, which is ideal for
   * I/O-bound operations like database access. Virtual Threads are much lighter than platform
   * threads and can be created in large numbers without significant overhead.
   */
  private ExecutorService virtualThreadExecutor;

  @Inject
  public ScriptStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
  }

  /**
   * Initialize the Virtual Thread executor.
   */
  @PostConstruct
  public void init() {
    // Create a Virtual Thread per task executor with a descriptive thread name prefix
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("script-store-").factory());
  }

  /**
   * Shutdown the Virtual Thread executor when the bean is destroyed.
   */
  @PreDestroy
  public void destroy() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }

  @Override
  public Script newScript() {
    return new ScriptData();
  }

  /**
   * Lists all scripts using Virtual Threads for improved I/O performance.
   * <p>
   * The @Transactional annotation ensures that the database operation occurs within a transaction,
   * while the Virtual Thread executor allows for efficient handling of I/O operations without
   * blocking platform threads.
   *
   * @return an immutable list of all scripts
   */
  @Transactional
  @Override
  public List<Script> list() {
    try {
      return virtualThreadExecutor.submit(() -> ImmutableList.copyOf(dao().browse())).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error listing scripts with Virtual Thread", e);
    }
  }

  /**
   * Gets a script by name using Virtual Threads for improved I/O performance.
   * <p>
   * The @Transactional annotation ensures that the database operation occurs within a transaction,
   * while the Virtual Thread executor allows for efficient handling of I/O operations without
   * blocking platform threads.
   *
   * @param name the script name
   * @return the script, or null if not found
   */
  @Transactional
  @Override
  public Script get(final String name) {
    try {
      return virtualThreadExecutor.submit(() -> dao().read(name).orElse(null)).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error getting script with Virtual Thread: " + name, e);
    }
  }

  /**
   * Creates a new script using Virtual Threads for improved I/O performance.
   * <p>
   * The @Transactional annotation ensures that the database operation occurs within a transaction,
   * while the Virtual Thread executor allows for efficient handling of I/O operations without
   * blocking platform threads.
   *
   * @param script the script to create
   */
  @Transactional
  @Override
  public void create(final Script script) {
    try {
      virtualThreadExecutor.submit(() -> {
        dao().create((ScriptData) script);
        return null;
      }).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error creating script with Virtual Thread: " + script.getName(), e);
    }
  }

  /**
   * Updates an existing script using Virtual Threads for improved I/O performance.
   * <p>
   * The @Transactional annotation ensures that the database operation occurs within a transaction,
   * while the Virtual Thread executor allows for efficient handling of I/O operations without
   * blocking platform threads.
   *
   * @param script the script to update
   */
  @Transactional
  @Override
  public void update(final Script script) {
    try {
      virtualThreadExecutor.submit(() -> {
        dao().update((ScriptData) script);
        return null;
      }).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error updating script with Virtual Thread: " + script.getName(), e);
    }
  }

  /**
   * Deletes a script using Virtual Threads for improved I/O performance.
   * <p>
   * The @Transactional annotation ensures that the database operation occurs within a transaction,
   * while the Virtual Thread executor allows for efficient handling of I/O operations without
   * blocking platform threads.
   *
   * @param script the script to delete
   */
  @Transactional
  @Override
  public void delete(final Script script) {
    try {
      virtualThreadExecutor.submit(() -> {
        dao().delete(script.getName());
        return null;
      }).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error deleting script with Virtual Thread: " + script.getName(), e);
    }
  }
}
