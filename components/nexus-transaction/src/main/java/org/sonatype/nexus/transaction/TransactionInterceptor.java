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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.StringTemplate;

import org.sonatype.goodies.common.ComponentSupport;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import static org.sonatype.nexus.transaction.UnitOfWork.openSession;
import static org.sonatype.nexus.transaction.UnitOfWork.peekTransaction;
import static org.sonatype.nexus.transaction.UnitOfWork.isVirtualThread;
import static org.sonatype.nexus.transaction.UnitOfWork.getThreadTypeDescription;

/**
 * Opens a transaction when entering a transactional method and closes it on exit.
 * Nested transactional methods proceed as normal inside the current transaction.
 * Supports both platform and virtual threads with proper context propagation.
 *
 * @since 3.0
 */
final class TransactionInterceptor
    extends ComponentSupport
    implements MethodInterceptor
{
  @Override
  public Object invoke(final MethodInvocation mi) throws Throwable {
    TransactionalStore<?> store = null;
    if (mi.getThis() instanceof TransactionalStore<?>) {
      store = (TransactionalStore<?>) mi.getThis();
    }
    
    // Detect if we're running in a virtual thread
    boolean virtualThread = isVirtualThread();
    if (log.isDebugEnabled()) {
      log.debug(STR."Transaction intercepted in \{getThreadTypeDescription()}");
    }

    Transaction tx = peekTransaction();
    if (tx != null) { // nested transactional session
      if (store != null) {
        tx.capture(store);
      }
      if (tx.isActive()) {
        return mi.proceed(); // no need to wrap active transaction
      }
      return proceedWithTransaction(mi, tx, virtualThread);
    }

    try (TransactionalSession<?> session = openSession(store, findSpec(mi.getMethod()).isolation(), virtualThread)) {
      return proceedWithTransaction(mi, session.getTransaction(), virtualThread);
    }
  }

  private Object proceedWithTransaction(final MethodInvocation mi, final Transaction tx, final boolean virtualThread) throws Throwable {
    Method method = mi.getMethod();
    Transactional spec = findSpec(method);

    if (log.isTraceEnabled()) {
      log.trace(STR."Invoking: \{spec} -> \{method} in \{getThreadTypeDescription()}");
    }

    // Pass virtual thread information to the wrapper for optimized handling
    return new TransactionalWrapper(spec, mi, virtualThread).proceedWithTransaction(tx);
  }

  private static final Transactional findSpec(final Method method) {
    Transactional spec = method.getAnnotation(Transactional.class);
    if (spec != null) {
      return spec;
    }
    // look for stereotypes; annotations marked with @Transactional
    for (final Annotation ann : method.getDeclaredAnnotations()) {
      spec = ann.annotationType().getAnnotation(Transactional.class);
      if (spec != null) {
        return spec;
      }
    }
    throw new IllegalStateException(STR."Missing @Transactional on: \{method}");
  }
}