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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.transaction.Transactional;

import com.google.common.collect.ImmutableList;

/**
 * MyBatis {@link ScriptStore} implementation with Java 21 Virtual Threads support.
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
   * Virtual thread executor for I/O-bound database operations.
   * Using virtual threads improves scalability for database operations without the overhead of platform threads.
   */
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public ScriptStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public Script newScript() {
    return new ScriptData();
  }

  /**
   * Lists all scripts using virtual threads for improved I/O performance.
   * The @Transactional annotation is preserved to maintain transaction boundaries.
   */
  @Transactional
  @Override
  public List<Script> list() {
    try {
      return virtualThreadExecutor.submit(() -> ImmutableList.copyOf(dao().browse())).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error listing scripts with virtual thread", e);
    }
  }

  /**
   * Gets a script by name using virtual threads for improved I/O performance.
   * The @Transactional annotation is preserved to maintain transaction boundaries.
   */
  @Transactional
  @Override
  public Script get(final String name) {
    try {
      return virtualThreadExecutor.submit(() -> dao().read(name).orElse(null)).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error getting script with virtual thread: " + name, e);
    }
  }

  /**
   * Creates a script using virtual threads for improved I/O performance.
   * The @Transactional annotation is preserved to maintain transaction boundaries.
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
      throw new RuntimeException("Error creating script with virtual thread: " + script.getName(), e);
    }
  }

  /**
   * Updates a script using virtual threads for improved I/O performance.
   * The @Transactional annotation is preserved to maintain transaction boundaries.
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
      throw new RuntimeException("Error updating script with virtual thread: " + script.getName(), e);
    }
  }

  /**
   * Deletes a script using virtual threads for improved I/O performance.
   * The @Transactional annotation is preserved to maintain transaction boundaries.
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
      throw new RuntimeException("Error deleting script with virtual thread: " + script.getName(), e);
    }
  }
}