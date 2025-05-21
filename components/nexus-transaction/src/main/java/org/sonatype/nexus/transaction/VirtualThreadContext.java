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
package org.sonatype.nexus.transaction;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Utility class for managing transaction context propagation across Virtual Thread boundaries.
 * 
 * @since 3.60
 */
public final class VirtualThreadContext {

  private static final ThreadLocal<String> TRANSACTION_ID = new ThreadLocal<>();
  private static final ConcurrentMap<Object, String> EVENT_TRANSACTION_CONTEXT = new ConcurrentHashMap<>();

  private VirtualThreadContext() {
    // Utility class, no instances
  }

  /**
   * Gets the current transaction ID from the thread-local context.
   * If no transaction ID exists, generates a new one.
   *
   * @return the current transaction ID
   */
  public static String getCurrentTransactionId() {
    String txId = TRANSACTION_ID.get();
    if (txId == null) {
      txId = UUID.randomUUID().toString();
      TRANSACTION_ID.set(txId);
    }
    return txId;
  }

  /**
   * Sets the transaction ID for the current thread.
   *
   * @param transactionId the transaction ID to set
   */
  public static void setCurrentTransactionId(String transactionId) {
    TRANSACTION_ID.set(transactionId);
  }

  /**
   * Clears the transaction ID for the current thread.
   */
  public static void clearCurrentTransactionId() {
    TRANSACTION_ID.remove();
  }

  /**
   * Associates a transaction context with an event object to propagate context across Virtual Thread boundaries.
   *
   * @param event the event object to associate with the current transaction context
   * @param <T> the type of the event
   * @return the event object (for method chaining)
   */
  public static <T> T propagateTransactionContext(T event) {
    String txId = getCurrentTransactionId();
    EVENT_TRANSACTION_CONTEXT.put(event, txId);
    return event;
  }

  /**
   * Retrieves the transaction context associated with an event and sets it as the current transaction context.
   *
   * @param event the event object containing the transaction context
   */
  public static void restoreTransactionContext(Object event) {
    String txId = EVENT_TRANSACTION_CONTEXT.remove(event);
    if (txId != null) {
      setCurrentTransactionId(txId);
    }
  }

  /**
   * Cleans up any transaction context associated with an event without restoring it.
   *
   * @param event the event object to clean up
   */
  public static void cleanupTransactionContext(Object event) {
    EVENT_TRANSACTION_CONTEXT.remove(event);
  }
}