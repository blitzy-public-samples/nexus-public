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

import org.sonatype.goodies.common.Loggers;

import org.aopalliance.intercept.Joinpoint;
import org.slf4j.Logger;

/**
 * Wraps an intercepted method with transactional behaviour.
 *
 * @since 3.0
 */
final class TransactionalWrapper
{
  private static final Logger log = Loggers.getLogger(TransactionalWrapper.class);

  private final Transactional spec;

  private final Joinpoint aspect;

  private final boolean tracing;
  
  private final boolean virtualThread;

  /**
   * Creates a new TransactionalWrapper.
   * 
   * @param spec the transactional specification
   * @param aspect the joinpoint to wrap
   */
  public TransactionalWrapper(final Transactional spec, final Joinpoint aspect) {
    this(spec, aspect, false);
  }
  
  /**
   * Creates a new TransactionalWrapper with virtual thread awareness.
   * 
   * @param spec the transactional specification
   * @param aspect the joinpoint to wrap
   * @param virtualThread whether the wrapper is being created in a virtual thread
   * @since 3.60
   */
  public TransactionalWrapper(final Transactional spec, final Joinpoint aspect, final boolean virtualThread) {
    this.spec = spec;
    this.aspect = aspect;
    this.virtualThread = virtualThread;

    tracing = log.isTraceEnabled();
  }

  /**
   * Applies transactional behaviour around the method call, supports automatic retries.
   * Handles thread migration for Virtual Threads by tracking thread ID at transaction boundaries.
   * Optimizes transaction handling for Virtual Threads to avoid pinning.
   */
  public Object proceedWithTransaction(final Transaction tx) throws Throwable {
    tx.reason(spec.reason());
    try {
      while (true) {
        boolean committed = false;
        Throwable throwing = null;
        Object result = null;
        // Store the thread ID at transaction begin to detect thread migration
        long beginThreadId = Thread.currentThread().threadId();
        String threadType = virtualThread ? "virtual" : "platform";
        
        try {
          if (tracing) {
            log.trace(STR."BEGIN \{tx} : \{aspect.getStaticPart()} [\{threadType} threadId=\{beginThreadId}]");
          }
          tx.begin();
          try {
            result = aspect.proceed();
            return result;
          }
          catch (final Throwable e) { // make sure we capture VM errors here (will be rethrown later)
            throwing = e;
          }
          finally {
            // Verify thread consistency during commit
            long commitThreadId = Thread.currentThread().threadId();
            if (throwing == null || instanceOf(throwing, spec.commitOn())) {
              if (tracing) {
                log.trace(STR."COMMIT \{tx} : \{aspect.getStaticPart()} [\{threadType} threadId=\{commitThreadId}]", throwing);
              }
              // Check for thread migration during transaction
              if (beginThreadId != commitThreadId) {
                log.debug(STR."Thread migration detected during transaction: begin=\{beginThreadId}, commit=\{commitThreadId}");
              }
              tx.commit();
              committed = true;
            }
            if (throwing != null) {
              throw throwing;
            }
          }
        }
        catch (final Exception e) { // ignore VM errors as here as we don't rollback/retry on them
          if (!committed) {
            // Verify thread consistency during rollback
            long rollbackThreadId = Thread.currentThread().threadId();
            if (tracing) {
              log.trace(STR."ROLLBACK \{tx} : \{aspect.getStaticPart()} [\{threadType} threadId=\{rollbackThreadId}]", e);
            }
            // Check for thread migration during transaction
            if (beginThreadId != rollbackThreadId) {
              log.debug(STR."Thread migration detected during transaction: begin=\{beginThreadId}, rollback=\{rollbackThreadId}");
            }
            tx.rollback();
            
            // Optimize retry logic for Virtual Threads
            if (instanceOf(e, spec.retryOn()) && tx.allowRetry(e)) {
              if (tracing) {
                log.trace(STR."RETRY \{tx} : \{aspect.getStaticPart()} [\{threadType} threadId=\{rollbackThreadId}]", e);
              }
              
              // For Virtual Threads, we can yield briefly before retrying to allow other threads to run
              if (virtualThread) {
                try {
                  // Use a minimal sleep to yield the Virtual Thread
                  Thread.sleep(1);
                }
                catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  throw e; // Don't retry if interrupted
                }
              }
              
              continue;
            }
            
            // only want to swallow commit exceptions distinct from 'throwing'
            if (throwing != e && instanceOf(e, spec.swallow())) {
              if (tracing) {
                log.trace(STR."SWALLOW \{tx} : \{aspect.getStaticPart()} [\{threadType} threadId=\{rollbackThreadId}]", e);
              }
              if (throwing != null) {
                throw throwing;
              }
              return result;
            }
          }
          if (throwing != null && throwing != e) {
            e.addSuppressed(throwing);
          }
          throw e;
        }
      }
    }
    finally {
      try {
        // Verify thread consistency during end
        long endThreadId = Thread.currentThread().threadId();
        String threadType = virtualThread ? "virtual" : "platform";
        tx.end();
        if (tracing) {
          log.trace(STR."END \{tx} [\{threadType} threadId=\{endThreadId}]");
        }
      }
      catch (Exception e) {
        long errorThreadId = Thread.currentThread().threadId();
        String threadType = virtualThread ? "virtual" : "platform";
        log.trace(STR."END \{tx} [\{threadType} threadId=\{errorThreadId}] failed", e);
      }
    }
  }

  /**
   * @return {@code true} if the given throwable is an instance of one of the types.
   */
  private static boolean instanceOf(final Throwable throwable, final Class<?>... types) {
    final Throwable cause = throwable.getCause();
    for (final Class<?> t : types) {
      if (t.isInstance(throwable) || t.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }
}