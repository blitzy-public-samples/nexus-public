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
package org.sonatype.nexus.repository.maven.tasks;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.maven.MavenIndexFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maven 2 publish MI indexes task.
 *
 * @since 3.0
 */
@Named
public class PublishMavenIndexTask
    extends RepositoryTaskSupport
{
  private static final Logger log = LoggerFactory.getLogger(PublishMavenIndexTask.class);

  @Override
  protected void execute(final Repository repository) {
    MavenIndexFacet mavenIndexFacet = repository.facet(MavenIndexFacet.class);
    
    // Use virtual threads for I/O-bound operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          Instant start = Instant.now();
          log.info(STR."Starting Maven index publishing for repository \{repository.getName()}");
          
          mavenIndexFacet.publishIndex();
          
          Instant end = Instant.now();
          Duration duration = Duration.between(start, end);
          log.info(STR."Completed Maven index publishing for repository \{repository.getName()} in \{duration.toMillis()} ms");
        }
        catch (IOException e) {
          log.error(STR."Failed to publish Maven index for repository \{repository.getName()}", e);
          throw new RuntimeException(e);
        }
        return null;
      }).get(); // Wait for the virtual thread to complete
    }
    catch (Exception e) {
      log.error(STR."Error executing Maven index publishing task for repository \{repository.getName()}", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  protected boolean appliesTo(final Repository repository) {
    return repository.getFormat().getValue().equals(Maven2Format.NAME);
  }

  @Override
  public String getMessage() {
    return STR."Publish Maven indexes of \{getRepositoryField()}";
  }
}