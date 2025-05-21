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
package org.sonatype.nexus.security.config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.sonatype.goodies.common.Locks;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;

/**
 * A mutable security contributor for testing purposes with enhanced thread safety for Java 21 Virtual Threads.
 * 
 * @since 3.1
 */
public class MutableTestSecurityContributor
    extends MutableSecurityContributor
{
  private final AtomicBoolean configRequested = new AtomicBoolean(false);
  
  private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(1);
  
  private final ReadWriteLock privIdLock = new ReentrantReadWriteLock();
  private final String privId;
  
  public MutableTestSecurityContributor() {
    this.privId = "priv-" + INSTANCE_COUNT.getAndIncrement();
  }

  @Override
  protected void initial(final SecurityConfiguration model) {
    model.addPrivilege(WildcardPrivilegeDescriptor.privilege("foo:bar:" + getId() + ":read"));
  }

  /**
   * Returns the unique identifier for this contributor.
   * Thread-safe for use with Virtual Threads.
   */
  public String getId() {
    Lock lock = Locks.read(privIdLock);
    try {
      return privId;
    }
    finally {
      lock.unlock();
    }
  }

  /**
   * Sets the configRequested flag in a thread-safe manner.
   * 
   * @param requested the new value for the configRequested flag
   */
  public void setConfigRequested(boolean requested) {
    configRequested.set(requested);
  }

  /**
   * Checks if configuration was requested in a thread-safe manner.
   * 
   * @return true if configuration was requested
   */
  public boolean wasConfigRequested() {
    return configRequested.get();
  }

  @Override
  public SecurityConfiguration getContribution() {
    setConfigRequested(true);
    return super.getContribution();
  }

  /**
   * Sets the dirty state of this contributor.
   * Thread-safe for use with Virtual Threads.
   * 
   * @param dirty whether to mark the model as dirty
   */
  public void setDirty(boolean dirty) {
    if (dirty) {
      setConfigRequested(false);
      apply((model, configurationManager) -> {
        // marks model as dirty
      });
    }
  }
  
  /**
   * Creates a new instance of this contributor that can be used in Virtual Thread tests.
   * 
   * @return a new thread-safe instance
   */
  public static MutableTestSecurityContributor forVirtualThreadTest() {
    return new MutableTestSecurityContributor();
  }
}
