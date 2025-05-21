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
package org.sonatype.nexus.internal.security.anonymous;

import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;

/**
 * {@link AnonymousConfiguration} event.
 * <p>
 * This interface is designed to be compatible with events fired from both platform threads and
 * Java 21 Virtual Threads. Implementations must ensure thread-safety when handling these events,
 * particularly when the events may be fired from Virtual Threads in high-concurrency scenarios.
 * <p>
 * Thread-safety considerations:
 * <ul>
 *   <li>Event handlers should avoid using ThreadLocal variables without proper Virtual Thread inheritance</li>
 *   <li>Implementations should be immutable or properly synchronized to handle concurrent access</li>
 *   <li>Long-running operations should be avoided in event handlers to prevent Virtual Thread parking issues</li>
 *   <li>Context propagation across thread boundaries should be handled explicitly</li>
 * </ul>
 *
 * @since 3.2
 */
public interface AnonymousConfigurationEvent
{
  /**
   * Indicates whether the event originated from the local node.
   * This method is thread-safe and can be called from any thread context including Virtual Threads.
   * 
   * @return true if the event is local, false otherwise
   */
  boolean isLocal();

  /**
   * Returns the ID of the remote node that originated this event, if applicable.
   * This method is thread-safe and can be called from any thread context including Virtual Threads.
   * 
   * @return the remote node ID or null if the event is local
   */
  String getRemoteNodeId();

  /**
   * Returns the anonymous configuration associated with this event.
   * Implementations must ensure the returned configuration is thread-safe or immutable
   * to support concurrent access from multiple threads, including Virtual Threads.
   * 
   * @return the anonymous configuration (thread-safe or immutable)
   * @since 3.10
   */
  AnonymousConfiguration getAnonymousConfiguration();
}