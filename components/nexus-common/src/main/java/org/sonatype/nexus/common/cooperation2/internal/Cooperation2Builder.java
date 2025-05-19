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
package org.sonatype.nexus.common.cooperation2.internal;

import java.util.Optional;

import org.sonatype.nexus.common.cooperation2.Cooperation2.Builder;
import org.sonatype.nexus.common.cooperation2.IOCall;
import org.sonatype.nexus.common.cooperation2.IOCheck;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Abstract implementation of {@link Builder}
 */
public abstract class Cooperation2Builder<RET>
    implements Builder<RET>
{
  protected boolean performWorkOnFail;
  
  /**
   * Flag indicating whether to use Virtual Threads for I/O-bound operations.
   * When set to true, the implementation may execute the work in a Virtual Thread
   * to improve throughput for operations that spend significant time waiting for I/O.
   * 
   * @since 3.60
   */
  protected boolean useVirtualThreads;
  
  /**
   * Flag indicating whether to propagate thread-local context to Virtual Threads.
   * When set to true, the implementation will ensure that relevant thread-local state
   * is captured from the parent thread and properly restored in the Virtual Thread.
   * 
   * @since 3.60
   */
  protected boolean propagateContext = true; // Default to true for backward compatibility

  protected IOCheck<RET> checkFunction = Optional::empty;

  protected final IOCall<RET> workFunction;

  protected Cooperation2Builder(final IOCall<RET> workFunction) {
    this.workFunction = checkNotNull(workFunction, "The work function for this co-operation is missing");
  }

  @Override
  public Cooperation2Builder<RET> checkFunction(final IOCheck<RET> checkFunction) {
    this.checkFunction = checkNotNull(checkFunction, "The check function for this co-operation is missing");
    return this;
  }

  @Override
  public Cooperation2Builder<RET> performWorkOnFail(final boolean performWorkOnFail) {
    this.performWorkOnFail = performWorkOnFail;
    return this;
  }
  
  @Override
  public Cooperation2Builder<RET> useVirtualThread(final boolean useVirtualThread) {
    this.useVirtualThreads = useVirtualThread;
    return this;
  }
  
  @Override
  public Cooperation2Builder<RET> propagateContext(final boolean propagateContext) {
    this.propagateContext = propagateContext;
    return this;
  }
}
