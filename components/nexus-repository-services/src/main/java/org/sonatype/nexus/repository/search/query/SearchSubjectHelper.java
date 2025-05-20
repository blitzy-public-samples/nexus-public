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
package org.sonatype.nexus.repository.search.query;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import com.google.common.annotations.VisibleForTesting;
import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper class used to temporarily associate a subject with a key for purposes of passing Shiro {@link Subject}s into
 * Elasticsearch. The caller is provided with a {@link SubjectRegistration} that should typically be used as part of a
 * try-with-resources block.
 *
 * @since 3.1
 */
@Named
@Singleton
public class SearchSubjectHelper
    extends ComponentSupport
{
  @VisibleForTesting
  final Map<String, Subject> subjects = new ConcurrentHashMap<>();
  
  private final ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

  /**
   * Registers the subject, returning a {@link SubjectRegistration}.
   * Uses Virtual Threads for improved concurrency.
   */
  public SubjectRegistration register(final Subject subject) {
    checkNotNull(subject);
    String uuid = generateUniqueId();
    
    // Use a virtual thread to handle the registration process
    Runnable registrationTask = () -> {
      if (subjects.putIfAbsent(uuid, subject) != null) {
        throw new IllegalStateException(STR."Duplicate UUID: \{uuid}");
      }
    };
    
    Thread virtualThread = virtualThreadFactory.newThread(registrationTask);
    virtualThread.start();
    try {
      virtualThread.join(); // Wait for the registration to complete
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Subject registration was interrupted", e);
    }
    
    return new SubjectRegistration(uuid);
  }

  /**
   * Gets the subject associated with the specified ID if present, throwing an exception if no subject exists.
   * Uses pattern matching for improved type safety and readability.
   */
  public Subject getSubject(final String subjectId) {
    checkNotNull(subjectId);
    var result = subjects.get(subjectId);
    
    return switch(result) {
      case null -> throw new IllegalArgumentException(STR."No subject for ID \{subjectId}");
      case Subject s -> s;
    };
  }

  /**
   * Unregisters the subject with the specified ID.
   */
  private void unregister(final String subjectId) {
    checkNotNull(subjectId);
    subjects.remove(subjectId);
  }
  
  /**
   * Generates a unique ID for subject registration using Java 21 concurrency features.
   */
  private String generateUniqueId() {
    return UUID.randomUUID().toString();
  }

  /**
   * {@link AutoCloseable} class demarcating the registration and unregistration of a subject.
   */
  public class SubjectRegistration
      implements AutoCloseable
  {
    private final String id;

    public SubjectRegistration(final String id) {
      this.id = checkNotNull(id);
    }

    public String getId() {
      return id;
    }

    @Override
    public void close() {
      unregister(id);
    }
  }
}