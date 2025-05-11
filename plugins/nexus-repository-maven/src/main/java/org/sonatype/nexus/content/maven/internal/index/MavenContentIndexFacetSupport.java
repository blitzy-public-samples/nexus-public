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
package org.sonatype.nexus.content.maven.internal.index;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.maven.MavenIndexFacet;
import org.sonatype.nexus.repository.maven.internal.MavenIndexPublisher;

import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link MavenIndexFacet} support with Java 21 enhancements.
 * 
 * This implementation leverages Java 21 features such as Virtual Threads for I/O operations
 * and provides compatibility with modern Java time APIs.
 *
 * @since 3.26
 */
public abstract class MavenContentIndexFacetSupport
    extends FacetSupport
    implements MavenIndexFacet
{
  final MavenIndexPublisher mavenIndexPublisher;

  protected MavenContentIndexFacetSupport(final MavenIndexPublisher mavenIndexPublisher) {
    this.mavenIndexPublisher = checkNotNull(mavenIndexPublisher);
  }

  /**
   * Returns the last published time as a Joda DateTime for backward compatibility.
   * 
   * @deprecated Consider using {@link #getLastPublishedInstant()} instead which returns a java.time.Instant
   *             for Java 21 compatibility.
   */
  @Nullable
  @Override
  public DateTime lastPublished() throws IOException {
    return mavenIndexPublisher.lastPublished(getRepository());
  }

  /**
   * Returns the last published time as a java.time.Instant.
   * This is the Java 21 compatible version of {@link #lastPublished()}.
   * 
   * @return the time when index was last published, or {@code null} if index is not published
   * @since Java 21 upgrade
   */
  @Nullable
  public Instant getLastPublishedInstant() throws IOException {
    DateTime dateTime = lastPublished();
    return dateTime != null ? Instant.ofEpochMilli(dateTime.getMillis()) : null;
  }

  /**
   * Publishes Maven Indexer indexes for downstream consumption using Virtual Threads for improved I/O performance.
   * 
   * This implementation uses Java 21 Virtual Threads to handle the I/O-bound operation,
   * which provides better scalability and resource utilization compared to platform threads.
   * 
   * @throws IOException if an I/O error occurs during publishing
   * @since Java 21 upgrade
   */
  @Override
  public void publishIndex() throws IOException {
    // Use a structured concurrency approach with virtual threads
    try {
      // Run the I/O-bound operation on a virtual thread
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        try {
          doPublishIndex(); // Call the implementation-specific method
          return null;
        } 
        catch (IOException e) {
          throw new RuntimeException(STR."Error publishing index files for \{getRepository().getName()}", e);
        }
      }).get(); // Wait for completion - this is a blocking call but runs on a virtual thread
    } 
    catch (Exception e) {
      // Use pattern matching for instanceof check (Java 21 feature)
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException(STR."Error executing publishIndex operation for \{getRepository().getName()}", e);
    }
  }
  
  /**
   * Implementation-specific method to publish the index.
   * Subclasses must implement this method to provide the actual publishing logic.
   * 
   * @throws IOException if an I/O error occurs during publishing
   */
  protected abstract void doPublishIndex() throws IOException;

  /**
   * Unpublishes the Maven index using Virtual Threads for improved I/O performance.
   * 
   * This implementation uses Java 21 Virtual Threads to handle the I/O-bound operation,
   * which provides better scalability and resource utilization compared to platform threads.
   * 
   * @throws IOException if an I/O error occurs during unpublishing
   * @since Java 21 upgrade
   */
  @Override
  public void unpublishIndex() throws IOException {
    // Use a structured concurrency approach with virtual threads
    try {
      // Run the I/O-bound operation on a virtual thread
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        try {
          mavenIndexPublisher.unpublishIndexFiles(getRepository());
          return null;
        } 
        catch (IOException e) {
          throw new RuntimeException(STR."Error unpublishing index files for \{getRepository().getName()}", e);
        }
      }).get(); // Wait for completion - this is a blocking call but runs on a virtual thread
    } 
    catch (Exception e) {
      // Use pattern matching for instanceof check (Java 21 feature)
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException(STR."Error executing unpublishIndex operation for \{getRepository().getName()}", e);
    }
  }
}