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
package org.sonatype.nexus.datastore.mybatis;

import java.sql.SQLException;

import org.sonatype.nexus.common.app.FrozenException;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.datastore.api.SerializedAccessException;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class EntityExecutorTest
{
  @Mock
  private Executor delegate;

  @Mock
  private FrozenChecker frozenChecker;

  private EntityExecutor underTest;

  @BeforeEach
  void setup() {
    underTest = new EntityExecutor(delegate, frozenChecker);
  }

  @Test
  void commitShouldDelegateToExecutor() throws SQLException {
    underTest.commit(true);
    verify(delegate).commit(true);

    doThrow(duplicateKeyException(), serializedAccessException(), missingStateException()).when(delegate).commit(true);
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.commit(true));
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.commit(true));
    Assertions.assertThrows(SQLException.class, () -> underTest.commit(true));
  }

  @Test
  void flushStatementsShouldDelegateToExecutor() throws SQLException {
    underTest.flushStatements();
    verify(delegate).flushStatements();

    when(delegate.flushStatements()).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.flushStatements());
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.flushStatements());
    Assertions.assertThrows(SQLException.class, () -> underTest.flushStatements());
  }

  @Test
  void query4ArgShouldDelegateToExecutor() throws SQLException {
    underTest.query(null, null, null, null);
    verify(delegate).query(null, null, null, null);

    when(delegate.query(null, null, null, null)).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.query(null,  null, null, null));
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.query(null,  null, null, null));
    Assertions.assertThrows(SQLException.class, () -> underTest.query(null,  null, null, null));
  }

  @Test
  void query6ArgShouldDelegateToExecutor() throws SQLException {
    underTest.query(null, null, null, null, null, null);
    verify(delegate).query(null, null, null, null, null, null);

    when(delegate.query(null, null, null, null, null, null)).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.query(null, null, null, null, null, null));
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.query(null, null, null, null, null, null));
    Assertions.assertThrows(SQLException.class, () -> underTest.query(null, null, null, null, null, null));
  }

  @Test
  void queryCursorShouldDelegateToExecutor() throws SQLException {
    underTest.queryCursor(null, null, null);
    verify(delegate).queryCursor(null, null, null);

    when(delegate.queryCursor(null, null, null)).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.queryCursor(null, null, null));
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.queryCursor(null, null, null));
    Assertions.assertThrows(SQLException.class, () -> underTest.queryCursor(null, null, null));
  }

  @Test
  void rollbackShouldDelegateToExecutor() throws SQLException {
    underTest.rollback(true);
    verify(delegate).rollback(true);

    doThrow(duplicateKeyException(), serializedAccessException(), missingStateException()).when(delegate).rollback(true);
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.rollback(true));
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.rollback(true));
    Assertions.assertThrows(SQLException.class, () -> underTest.rollback(true));
  }

  @Test
  void updateShouldDelegateToExecutor() throws SQLException {
    MappedStatement ms = mock(MappedStatement.class);
    underTest.update(ms, null);
    verify(delegate).update(ms, null);

    when(delegate.update(ms, null)).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
    Assertions.assertThrows(DuplicateKeyException.class, () -> underTest.update(ms, null));
    Assertions.assertThrows(SerializedAccessException.class, () -> underTest.update(ms, null));
    Assertions.assertThrows(SQLException.class, () -> underTest.update(ms, null));
  }

  @Test
  void updateShouldThrowFrozenExceptionWhenSystemIsFrozen() throws SQLException {
    MappedStatement ms = mock(MappedStatement.class);
    doThrow(new FrozenException("Frozen")).when(frozenChecker).checkFrozen(ms);

    Assertions.assertThrows(FrozenException.class, () -> underTest.update(ms, null));
    verify(delegate, never()).update(ms, null);
  }

  private static SQLException duplicateKeyException() {
    return new SQLException("Duplicate Key", DuplicateKeyException.SQL_STATE);
  }

  private static SQLException serializedAccessException() {
    return new SQLException("Isolation", SerializedAccessException.SQL_STATE);
  }

  private static SQLException missingStateException() {
    return new SQLException("Some hikari error");
  }
}