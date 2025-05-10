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

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.base.Suppliers;
import com.google.inject.Guice;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test unit-of-work behaviour.
 */
@Tag("Java21TestGroup")
public class UnitOfWorkTest
    extends TestSupport
{
  @Test
  public void testCannotBeginNullWork() {
    assertThrows(NullPointerException.class, () -> UnitOfWork.begin(null));
  }

  @Test
  public void testCannotEndNoWork() {
    assertThrows(IllegalStateException.class, () -> UnitOfWork.end());
  }

  @SuppressWarnings("java:S2699") // sonar expects assertions, but best to let this exception bubble up
  @Test
  public void testCanPauseNoWork() {
    UnitOfWork.resume(UnitOfWork.pause());
  }

  @Test
  public void testCannotResumeTwice() {
    UnitOfWork.begin(Suppliers.<TransactionalSession<Transaction>> ofInstance(null));
    try {
      UnitOfWork work = UnitOfWork.pause();
      UnitOfWork.resume(work);
      assertThrows(IllegalStateException.class, () -> UnitOfWork.resume(work));
    }
    finally {
      UnitOfWork.end();
    }
  }

  @Test
  public void testCannotStartTransactionWithNoWork() {
    assertThrows(IllegalStateException.class, () -> 
        Guice.createInjector(new TransactionModule()).getInstance(ExampleMethods.class).transactional());
  }
}