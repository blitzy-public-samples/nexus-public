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

import java.io.IOException;
import java.util.ConcurrentModificationException;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.base.Suppliers;
import com.google.inject.Guice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assertions;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.transaction.Transactional.DEFAULT_REASON;

/**
 * Test transactional behaviour.
 */
@SuppressWarnings("boxing")
@ExtendWith(MockitoExtension.class)
public class TransactionalTest
    extends TestSupport
{
  ExampleMethods methods = Guice.createInjector(new TransactionModule()).getInstance(ExampleMethods.class);

  @Mock
  TransactionalSession<Transaction> session;

  @Mock
  Transaction tx;

  boolean isActive;

  boolean throwExceptionOnCommit;

  @BeforeEach
  public void setUp() throws Exception {
    when(session.getTransaction()).thenReturn(tx);
    UnitOfWork.begin(Suppliers.ofInstance(session));

    when(tx.isActive()).thenAnswer(new Answer<Boolean>()
    {
      @Override
      public Boolean answer(final InvocationOnMock invocation) throws Throwable {
        return isActive;
      }
    });

    doAnswer(new Answer<Void>()
    {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = true;
        return null;
      }
    }).when(tx).begin();

    doAnswer(new Answer<Void>()
    {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = false;
        if (throwExceptionOnCommit) {
          throw new ConcurrentModificationException();
        }
        return null;
      }
    }).when(tx).commit();

    doAnswer(new Answer<Void>()
    {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = false;
        return null;
      }
    }).when(tx).rollback();
  }

  @AfterEach
  public void tearDown() {
    UnitOfWork.end();
  }

  @Test
  public void testNonTransactional() {

    methods.nonTransactional();
    methods.nonTransactional();
    methods.nonTransactional();

    verifyNoMoreInteractions(tx);
  }

  @Test
  public void testTransactional() throws Exception {

    methods.transactional();
    methods.transactional();
    methods.transactional();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testCustomReason() throws Exception {

    methods.customReason();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason("Testing!");
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testPauseResume() throws Exception {
    final TransactionalSession<Transaction> session2 = mock(TransactionalSession.class);
    final Transaction tx2 = mock(Transaction.class);
    when(session2.getTransaction()).thenReturn(tx2);

    methods.transactional();

    final UnitOfWork work = UnitOfWork.pause();
    try {
      UnitOfWork.begin(Suppliers.ofInstance(session2));
      try {
        methods.transactional();
      }
      finally {
        UnitOfWork.end();
      }
    }
    finally {
      UnitOfWork.resume(work);
    }
    methods.transactional();

    InOrder order = inOrder(session, tx, session2, tx2);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session2).getTransaction();
    order.verify(tx2).reason(DEFAULT_REASON);
    order.verify(tx2).begin();
    order.verify(tx2).commit();
    order.verify(tx2).end();
    order.verify(session2).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx, session2, tx2);
  }

  @Test
  public void testBatchTransactional() throws Exception {
    UnitOfWork.beginBatch(Suppliers.ofInstance(session));
    try {
      methods.transactional();
      methods.transactional();
      methods.transactional();
    }
    finally {
      UnitOfWork.end();
    }

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testNested() throws Exception {

    methods.outer();
    methods.outer();
    methods.outer();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testBatchNested() throws Exception {

    UnitOfWork.beginBatch(Suppliers.ofInstance(session));
    try {
      methods.outer();
      methods.outer();
      methods.outer();
    }
    finally {
      UnitOfWork.end();
    }

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testNestedTransactionalStoreIsCaptured() throws Exception {

    methods.captureNestedStore();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).capture(methods.nestedStore);
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @SuppressWarnings("java:S2699") // sonar expects assertions, but best to let this exception bubble up
  @Test
  public void testCanSeeTransactionInsideTransactional() {
    methods.canSeeTransactionInsideTransactional();
  }

  @Test
  public void testCannotSeeTransactionOutsideTransactional() {
    Assertions.assertThrows(IllegalStateException.class, () -> {
      methods.cannotSeeTransactionOutsideTransactional();
    });
  }

  @Test
  public void testRollbackOnCheckedException() throws Exception {
    Assertions.assertThrows(IOException.class, () -> {
      try {
        methods.rollbackOnCheckedException();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testRollbackOnUncheckedException() throws Exception {
    Assertions.assertThrows(IllegalStateException.class, () -> {
      try {
        methods.rollbackOnUncheckedException();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testCommitOnCheckedException() throws Exception {
    Assertions.assertThrows(IOException.class, () -> {
      try {
        methods.commitOnCheckedException();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).commit();
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testCommitOnUncheckedException() throws Exception {
    Assertions.assertThrows(IllegalStateException.class, () -> {
      try {
        methods.commitOnUncheckedException();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).commit();
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testRetrySuccessOnCheckedException() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true);

    methods.setCountdownToSuccess(3);
    methods.retryOnCheckedException();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testRetryFailureOnCheckedException() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    methods.setCountdownToSuccess(100);
    Assertions.assertThrows(IOException.class, () -> {
      try {
        methods.retryOnCheckedException();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(IOException.class));
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(IOException.class));
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testRetrySuccessOnUncheckedException() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true);

    methods.setCountdownToSuccess(3);
    methods.retryOnUncheckedException();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testRetryFailureOnUncheckedException() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    methods.setCountdownToSuccess(100);
    Assertions.assertThrows(IllegalStateException.class, () -> {
      try {
        methods.retryOnUncheckedException();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(IllegalStateException.class));
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(IllegalStateException.class));
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testRetrySuccessOnExceptionCause() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true);

    methods.setCountdownToSuccess(3);
    methods.retryOnExceptionCause();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  @Test
  public void testRetryFailureOnExceptionCause() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    methods.setCountdownToSuccess(100);
    Assertions.assertThrows(IllegalStateException.class, () -> {
      try {
        methods.retryOnExceptionCause();
      }
      finally {
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(IllegalStateException.class));
        order.verify(tx).begin();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(IllegalStateException.class));
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testCannotBeginWorkInTransaction() {
    Assertions.assertThrows(IllegalStateException.class, () -> {
      methods.beginWorkInTransaction();
    });
  }

  @Test
  public void testCannotEndWorkInTransaction() {
    Assertions.assertThrows(IllegalStateException.class, () -> {
      methods.endWorkInTransaction();
    });
  }

  @Test
  public void testRetryOnCommitFailure() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    Assertions.assertThrows(ConcurrentModificationException.class, () -> {
      try {
        throwExceptionOnCommit = true;
        methods.retryOnCommitFailure();
      }
      finally {
        throwExceptionOnCommit = false;
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).commit();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(ConcurrentModificationException.class));
        order.verify(tx).begin();
        order.verify(tx).commit();
        order.verify(tx).rollback();
        order.verify(tx).allowRetry(any(ConcurrentModificationException.class));
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testSwallowCommitFailure() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    try {
      throwExceptionOnCommit = true;
      methods.swallowCommitFailure();
    }
    finally {
      throwExceptionOnCommit = false;
      InOrder order = inOrder(session, tx);
      order.verify(session).getTransaction();
      order.verify(tx).reason(DEFAULT_REASON);
      order.verify(tx).begin();
      order.verify(tx).commit();
      order.verify(tx).rollback();
      order.verify(tx).end();
      order.verify(session).close();
      verifyNoMoreInteractions(session, tx);
    }
  }

  @Test
  public void testSwallowWontHideOriginalCause() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    Assertions.assertThrows(IllegalStateException.class, () -> {
      try {
        throwExceptionOnCommit = true;
        methods.commitOnUncheckedSwallowCommitFailure();
      }
      finally {
        throwExceptionOnCommit = false;
        InOrder order = inOrder(session, tx);
        order.verify(session).getTransaction();
        order.verify(tx).reason(DEFAULT_REASON);
        order.verify(tx).begin();
        order.verify(tx).commit();
        order.verify(tx).rollback();
        order.verify(tx).end();
        order.verify(session).close();
        verifyNoMoreInteractions(session, tx);
      }
    });
  }

  @Test
  public void testCanUseStereotypeAnnotation() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true);

    methods.setCountdownToSuccess(3);
    methods.canUseStereotypeAnnotation();

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }
}
