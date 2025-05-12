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
package org.sonatype.nexus.testsupport.jupiter;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.sonatype.nexus.datastore.api.DataAccess;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.testdb.DataSessionRule;

/**
 * JUnit Jupiter extension for managing {@link DataSession} instances in tests.
 * <p>
 * This extension provides JUnit Jupiter integration for the Nexus Repository
 * data access layer, allowing tests to easily work with database sessions and
 * transactions. It replaces the JUnit 4 {@link DataSessionRule} with a modern
 * Jupiter extension that supports parameter injection and proper lifecycle management.
 * <p>
 * The extension automatically creates and manages a {@link DataSession} for each test,
 * injecting it as a parameter into test methods that request it. It also ensures proper
 * cleanup of database resources after each test.
 * <p>
 * This implementation is compatible with Java 21 and supports virtual thread execution
 * for concurrent database operations testing.
 *
 * @since 3.62
 */
public class DataSessionExtension
    implements BeforeEachCallback, AfterEachCallback, ParameterResolver
{
  private static final String NAMESPACE = "org.sonatype.nexus.testsupport.jupiter";
  private static final String DATA_SESSION_KEY = "dataSession";

  private final DataSessionRule sessionRule;

  /**
   * Creates a new extension with default configuration.
   */
  public DataSessionExtension() {
    this.sessionRule = new DataSessionRule();
  }

  /**
   * Creates a new extension with the specified DataAccess types.
   *
   * @param types the DataAccess types to register with the session
   */
  public DataSessionExtension(Class<? extends DataAccess>... types) {
    this.sessionRule = new DataSessionRule().access(types);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // Create a new session for this test
    DataSession<?> session = sessionRule.openSession(DataStoreManager.DEFAULT_DATASTORE_NAME);
    getStore(context).put(DATA_SESSION_KEY, session);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // Close the session after the test
    DataSession<?> session = getSession(context);
    if (session != null && session.isOpen()) {
      session.close();
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType() == DataSession.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
    return getSession(extensionContext);
  }

  /**
   * Gets the DataSession for the current test context.
   *
   * @param context the extension context
   * @return the DataSession, or null if none exists
   */
  private DataSession<?> getSession(ExtensionContext context) {
    return (DataSession<?>) getStore(context).get(DATA_SESSION_KEY);
  }

  /**
   * Gets the store for this extension.
   *
   * @param context the extension context
   * @return the store
   */
  private ExtensionContext.Store getStore(ExtensionContext context) {
    return context.getStore(ExtensionContext.Namespace.create(NAMESPACE, context.getRequiredTestMethod()));
  }

  /**
   * Gets the DataStore associated with this extension.
   *
   * @return the DataStore
   */
  public DataStore<?> getDataStore() {
    return sessionRule.getDataStore(DataStoreManager.DEFAULT_DATASTORE_NAME);
  }
}