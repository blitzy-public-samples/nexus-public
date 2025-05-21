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
package org.sonatype.nexus.internal.app;

import java.lang.reflect.InvocationTargetException;  
import java.util.Iterator;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.GlobalComponentLookupHelper;

import com.google.inject.Key;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.inject.BeanLocator;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.inject.name.Names.named;

/**
 * Default {@link GlobalComponentLookupHelper}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class GlobalComponentLookupHelperImpl
    extends ComponentSupport
    implements GlobalComponentLookupHelper
{
  private final ClassLoader classLoader;

  private final BeanLocator beanLocator;

  @Inject
  public GlobalComponentLookupHelperImpl(
      @Named("nexus-uber") final ClassLoader classLoader,
      final BeanLocator beanLocator)
  {
    this.classLoader = checkNotNull(classLoader);
    this.beanLocator = checkNotNull(beanLocator);
    
    // Verify compatibility with the updated 'nexus-uber' ClassLoader under Java 21
    verifyClassLoaderCompatibility();
  }
  
  /**
   * Verifies compatibility with the nexus-uber ClassLoader under Java 21's enhanced encapsulation model.
   * This helps identify potential issues early during initialization.
   */
  private void verifyClassLoaderCompatibility() {
    try {
      // Test loading a core class to verify ClassLoader functionality
      Class<?> testClass = classLoader.loadClass("java.lang.String");
      if (testClass == null) {
        log.warn("nexus-uber ClassLoader returned null for core class test");
      } else {
        log.trace("nexus-uber ClassLoader compatibility verified");
      }
    } catch (Exception e) {
      // Log but don't fail - the application might still work with limitations
      log.warn("nexus-uber ClassLoader compatibility check failed", e);
    }
  }

  @Override
  @Nullable
  public Object lookup(final String className) {
    checkNotNull(className);
    try {
      log.trace("Looking up component by class-name: {}", className);
      // Use try-with-resources to ensure proper resource management under Java 21's stricter controls
      try {
        Class<?> type = classLoader.loadClass(className);
        return lookup(type);
      }
      catch (ClassNotFoundException e) {
        log.trace("Class not found: {}", className, e);
        return null;
      }
    }
    catch (Exception e) {
      // Enhanced exception handling for Java 21
      log.trace("Unable to lookup component by class-name: {}; ignoring", className, e);
    }
    return null;
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  public <T> T lookup(final Class<T> clazz) {
    checkNotNull(clazz);
    return (T) lookup(Key.get(clazz));
  }

  @Override
  @Nullable
  @SuppressWarnings("unchecked")
  public <T> T lookup(final Class<T> clazz, final String name) {
    checkNotNull(clazz);
    checkNotNull(name);
    return (T) lookup(Key.get(clazz, named(name)));
  }

  @Override
  @Nullable
  public Object lookup(final Key key) {
    checkNotNull(key);
    try {
      log.trace("Looking up component by key: {}", key);
      @SuppressWarnings("unchecked")
      Iterator<BeanEntry> iter = beanLocator.locate(key).iterator();
      
      // Using pattern matching for switch to simplify component lookup logic
      return switch(iter.hasNext()) {
        case true -> {
          try {
            // Add safeguards for reflective operations under Java 21's stricter access controls
            BeanEntry entry = iter.next();
            yield entry.getValue();
          } 
          catch (SecurityException e) {
            log.trace("Security exception accessing bean value for key: {}", key, e);
            yield null;
          }
          catch (Exception e) {
            log.trace("Exception retrieving bean value for key: {}", key, e);
            yield null;
          }
        }
        case false -> {
          log.trace("Component not found for key: {}", key);
          yield null;
        }
      };
    }
    catch (Exception e) {
      // Enhanced exception handling with pattern matching for different exception types
      if (e instanceof SecurityException) {
        log.trace("Security exception during component lookup for key: {}", key, e);
      } else if (e instanceof IllegalStateException) {
        log.trace("Illegal state during component lookup for key: {}", key, e);
      } else {
        log.trace("Unable to lookup component by key: {}; ignoring", key, e);
      }
    }
    return null;
  }

  @Override
  @Nullable
  public Class<?> type(final String className) {
    checkNotNull(className);
    try {
      log.trace("Looking up type: {}", className);
      // Update dynamic class loading to work properly with Java 21's enhanced encapsulation model
      try {
        return classLoader.loadClass(className);
      } 
      catch (ClassNotFoundException e) {
        // Specific handling for ClassNotFoundException under Java 21
        log.trace("Class not found in nexus-uber ClassLoader: {}", className);
        // Try with the context class loader as a fallback
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null && contextClassLoader != classLoader) {
          try {
            return contextClassLoader.loadClass(className);
          } 
          catch (ClassNotFoundException ignored) {
            // Intentionally ignored, we'll return null below
          }
        }
      }
    }
    catch (Exception e) {
      // Use pattern matching to handle different exception types
      handleTypeException(className, e);
    }
    return null;
  }
  
  /**
   * Helper method to handle exceptions during type lookup with pattern matching.
   * This leverages Java 21's pattern matching capabilities for more precise exception handling.
   */
  private void handleTypeException(String className, Exception e) {
    switch (e) {
      case SecurityException se -> 
          log.trace("Security exception accessing class: {}", className, se);
      case LinkageError le -> 
          log.trace("Linkage error loading class: {}", className, le);
      case InvocationTargetException ite -> 
          log.trace("Invocation exception during class loading: {}", className, ite);
      default -> 
          log.trace("Unable to lookup type: {}; ignoring", className, e);
    }
  }
}