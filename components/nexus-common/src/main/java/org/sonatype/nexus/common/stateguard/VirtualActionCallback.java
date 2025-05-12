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
 * Callback interface for handling the result of an asynchronous action executed in a virtual thread.
 *
 * @param <V> the return type of the action
 * @since Java 21
 */
public interface VirtualActionCallback<V>
{
  /**
   * Called when the action completes successfully.
   *
   * @param result the result of the action
   */
  void onSuccess(V result);

  /**
   * Called when the action fails with an exception.
   *
   * @param exception the exception that occurred
   */
  void onFailure(Exception exception);
}