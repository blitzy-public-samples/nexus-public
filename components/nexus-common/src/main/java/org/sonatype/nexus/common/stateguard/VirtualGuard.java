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
package org.sonatype.nexus.common.stateguard;

/**
 * Virtual guard allows execution of an action in a virtual thread if the current state is acceptable.
 * This is particularly useful for I/O-bound operations to improve scalability.
 *
 * @since Java 21
 */
public interface VirtualGuard
{
  /**
   * Run action asynchronously in a virtual thread if current state is allowed.
   *
   * @param action the action to execute
   * @param callback the callback to handle the result or exception
   * @param <V> the return type of the action
   */
  <V> void runAsync(Action<V> action, VirtualActionCallback<V> callback);
}