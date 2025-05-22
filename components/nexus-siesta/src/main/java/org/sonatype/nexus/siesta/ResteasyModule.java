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
package org.sonatype.nexus.siesta;

import java.util.concurrent.Callable;

import org.sonatype.nexus.common.thread.VirtualThreadHelper;
import org.sonatype.nexus.siesta.internal.resteasy.ComponentContainerImpl;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.jboss.resteasy.core.Dispatcher;

/**
 * RESTEasy module for RESTEasy 6.2.7.Final with Java 21 Virtual Thread support.
 *
 * @since 3.0
 */
public class ResteasyModule
  extends AbstractModule
{
  @Override
  protected void configure() {
    // eager binding so we can register RESTEasy with JAX-RS as early as possible
    // ensure proper component lifecycle with Java 21
    bind(ComponentContainer.class).to(ComponentContainerImpl.class).asEagerSingleton();
  }

  /**
   * Expose RESTEasy {@link Dispatcher} binding with Virtual Thread context propagation support.
   * 
   * This ensures that when the dispatcher is used across virtual thread boundaries,
   * the context is properly propagated.
   */
  @Provides
  @Singleton
  public Dispatcher dispatcher(final ComponentContainer container) {
    // Get the original dispatcher from the container
    Dispatcher originalDispatcher = ((ComponentContainerImpl)container).getDispatcher();
    
    // For Java 21 compatibility, ensure proper context propagation with Virtual Threads
    // by wrapping operations that might be executed across virtual thread boundaries
    return new DispatcherVirtualThreadWrapper(originalDispatcher);
  }
  
  /**
   * Wrapper for Dispatcher that ensures Virtual Thread context propagation.
   * This class ensures that any context associated with the current thread is properly
   * captured and restored when operations are performed across virtual thread boundaries.
   */
  private static class DispatcherVirtualThreadWrapper implements Dispatcher {
    private final Dispatcher delegate;
    
    public DispatcherVirtualThreadWrapper(Dispatcher delegate) {
      this.delegate = delegate;
    }
    
    /**
     * Helper method to wrap operations with Virtual Thread context propagation.
     * This ensures that thread context is properly maintained when operations
     * might be suspended and resumed on different carrier threads.
     */
    private <T> T withVirtualThreadContext(Callable<T> operation) throws Exception {
      // Use VirtualThreadHelper to ensure context propagation
      return VirtualThreadHelper.runWithThreadContext(operation);
    }
    
    // Delegate all methods to the original dispatcher, ensuring context propagation
    // Only override methods that might be executed across virtual thread boundaries
    
    @Override
    public Object invoke(Object request) {
      try {
        return withVirtualThreadContext(() -> delegate.invoke(request));
      }
      catch (Exception e) {
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new RuntimeException("Error invoking dispatcher with virtual thread context", e);
      }
    }
    
    // Delegate all other methods directly to the original dispatcher
    // Add context propagation only to methods that might cross virtual thread boundaries
    
    // Standard delegation methods
    @Override
    public void addHttpPreprocessor(Object httpPreprocessor) {
      delegate.addHttpPreprocessor(httpPreprocessor);
    }
    
    @Override
    public org.jboss.resteasy.spi.Registry getRegistry() {
      return delegate.getRegistry();
    }
    
    @Override
    public org.jboss.resteasy.spi.ResteasyProviderFactory getProviderFactory() {
      return delegate.getProviderFactory();
    }
    
    @Override
    public void removeHttpPreprocessor(Object httpPreprocessor) {
      delegate.removeHttpPreprocessor(httpPreprocessor);
    }
  }
}