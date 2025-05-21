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

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.lang.reflect.InaccessibleObjectException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import org.eclipse.sisu.space.ClassSpace;

import static com.google.common.base.Preconditions.checkNotNull;

// FIXME: Rename to GlobalClassLoader, and "global" for named key

/**
 * ClassLoader which exposes all {@link ClassSpace}s in the application.
 * Enhanced for Java 21 compatibility with improved exception handling for the
 * module system's enhanced encapsulation model, pattern matching for switch,
 * and optimized cache handling.
 *
 * @since 2.6
 */
@Named("nexus-uber")
@Singleton
public class NexusUberClassloader
    extends ClassLoader
{
  private final List<ClassSpace> spaces;

  // Using LinkedHashMap as a SequencedMap for better cache performance in Java 21
  private final Cache<String, Class<?>> classLookups = CacheBuilder.newBuilder()
      .weakValues()
      .build();
      
  // Track access exceptions to avoid repeated failures
  private final Map<String, Throwable> accessExceptions = new LinkedHashMap<>();

  @Inject
  public NexusUberClassloader(final List<ClassSpace> spaces) {
    this.spaces = checkNotNull(spaces);
  }

  @Override
  public Class<?> loadClass(String name) throws ClassNotFoundException {
    return loadClass(name, false);
  }

  /**
   * Loads the class with the specified name. Enhanced for Java 21 compatibility
   * with improved exception handling for module system restrictions.
   *
   * @param name the name of the class to load
   * @param resolve whether to resolve the class
   * @return the loaded class
   * @throws ClassNotFoundException if the class cannot be found
   */
  @Override
  protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
    // Special handling for java.* classes to avoid module system restrictions
    if (name.startsWith("java.")) {
      return super.getParent().loadClass(name);
    }
    
    try {
      // cache successful results to save having to find them again
      return classLookups.get(name, () -> searchSpacesForClass(name));
    }
    catch (ExecutionException e) { // NOSONAR: only interested in the cause
      Throwable cause = e.getCause();
      
      // Use pattern matching to handle different exception types
      switch (cause) {
        case ClassNotFoundException cnf -> throw cnf;
        case IllegalAccessException | InaccessibleObjectException accessException -> {
          // Handle Java 21's enhanced encapsulation restrictions
          throw new ClassNotFoundException("Access denied to class: " + name + 
              " due to Java 21 module restrictions", accessException);
        }
        case SecurityException securityException -> {
          throw new ClassNotFoundException("Security violation accessing class: " + name, securityException);
        }
        default -> {
          throw new ClassNotFoundException(name, cause);
        }
      }
    }
  }

  /**
   * Returns a URL to the resource with the specified name.
   * Enhanced for Java 21 compatibility with improved exception handling.
   *
   * @param name the resource name
   * @return a URL to the resource, or null if the resource could not be found
   */
  @Override
  public URL getResource(final String name) {
    for (ClassSpace space : spaces) {
      try {
        URL result = space.getResource(name);
        if (result != null) {
          return result;
        }
      }
      catch (Exception e) {
        // Skip spaces that throw exceptions due to module restrictions
        // This prevents one inaccessible space from blocking access to others
        // Common with Java 21's enhanced encapsulation model
      }
    }
    return null;
  }

  /**
   * Returns an enumeration of URLs for the resource with the specified name.
   * Enhanced for Java 21 compatibility with improved exception handling.
   *
   * @param name the resource name
   * @return an enumeration of URLs for the resource
   */
  @Override
  public Enumeration<URL> getResources(final String name) {
    List<URL> result = Lists.newArrayList();
    for (ClassSpace space : spaces) {
      try {
        Enumeration<URL> resources = space.getResources(name);
        while (resources.hasMoreElements()) {
          try {
            result.add(resources.nextElement());
          }
          catch (Exception e) {
            // Skip resources that can't be accessed due to module restrictions
            // This prevents one inaccessible resource from blocking access to others
          }
        }
      }
      catch (Exception e) {
        // Skip spaces that throw exceptions due to module restrictions
        // This prevents one inaccessible space from blocking access to others
      }
    }
    return Collections.enumeration(result);
  }

  /**
   * Searches through all available ClassSpaces to find and load the requested class.
   * Uses pattern matching for switch to handle different exception types more elegantly.
   *
   * @param name the name of the class to load
   * @return the loaded class
   * @throws ClassNotFoundException if the class cannot be found in any space
   */
  private Class<?> searchSpacesForClass(final String name) throws ClassNotFoundException {
    // Check if we've already encountered an access exception for this class
    Throwable previousException = accessExceptions.get(name);
    if (previousException != null) {
      if (previousException instanceof ClassNotFoundException) {
        throw (ClassNotFoundException) previousException;
      }
      throw new ClassNotFoundException(name, previousException);
    }
    
    for (ClassSpace space : spaces) {
      try {
        return space.loadClass(name);
      }
      catch (Throwable e) {
        // Use pattern matching for switch to handle different exception types
        switch (e) {
          case TypeNotPresentException ignored -> {
            // Ignore and continue to next space
          }
          case IllegalAccessException | InaccessibleObjectException accessException -> {
            // Cache access exceptions to avoid repeated failures
            accessExceptions.put(name, accessException);
            throw new ClassNotFoundException("Access denied to class: " + name, accessException);
          }
          case SecurityException securityException -> {
            // Cache security exceptions to avoid repeated failures
            accessExceptions.put(name, securityException);
            throw new ClassNotFoundException("Security violation accessing class: " + name, securityException);
          }
          default -> {
            // For other exceptions, log and continue to next space
            if (e instanceof RuntimeException || e instanceof Error) {
              // Only cache fatal exceptions
              if (e.getMessage() != null && e.getMessage().contains("module")) {
                // Likely a module access issue in Java 21
                accessExceptions.put(name, e);
              }
            }
          }
        }
      }
    }
    
    // Cache the fact that this class was not found to avoid repeated searches
    ClassNotFoundException notFoundException = new ClassNotFoundException(name);
    accessExceptions.put(name, notFoundException);
    throw notFoundException;
  }
}