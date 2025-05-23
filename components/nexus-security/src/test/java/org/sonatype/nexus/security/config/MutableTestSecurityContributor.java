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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;

/**
 * A mutable security contributor for testing purposes.
 * Enhanced for thread-safety to support testing with Java 21 Virtual Threads.
 */
public class MutableTestSecurityContributor
    extends MutableSecurityContributor
{
  // Using AtomicBoolean for thread-safe access across virtual threads
  private final AtomicBoolean configRequested = new AtomicBoolean(false);

  // Using AtomicInteger for thread-safe counter increments across virtual threads
  private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(1);

  // Initialized once during construction and immutable thereafter
  private final String privId;

  /**
   * Constructs a new instance with a unique privilege ID.
   */
  public MutableTestSecurityContributor() {
    this.privId = "priv-" + INSTANCE_COUNT.getAndIncrement();
  }

  @Override
  protected void initial(final SecurityConfiguration model) {
    model.addPrivilege(WildcardPrivilegeDescriptor.privilege("foo:bar:" + privId + ":read"));
  }

  /**
   * Returns the unique privilege ID for this contributor.
   */
  public String getId() {
    return privId;
  }

  /**
   * Thread-safe method to set the config requested flag.
   */
  public void setConfigRequested(boolean configRequested) {
    this.configRequested.set(configRequested);
  }

  /**
   * Thread-safe method to check if config was requested.
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
   * Thread-safe method to mark the model as dirty.
   */
  public void setDirty(boolean dirty) {
    if (dirty) {
      setConfigRequested(false);
      apply((model, configurationManager) -> {
        // marks model as dirty
      });
    }
  }
}