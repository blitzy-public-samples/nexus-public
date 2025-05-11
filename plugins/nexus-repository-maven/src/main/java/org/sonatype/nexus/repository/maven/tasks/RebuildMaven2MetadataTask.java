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

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.common.log.LoggingUtil;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.ARTIFACTID_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.BASEVERSION_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.CASCADE_REBUILD;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.GROUPID_FIELD_ID;
import static org.sonatype.nexus.repository.maven.tasks.RebuildMaven2MetadataTaskDescriptor.REBUILD_CHECKSUMS;

/**
 * Maven 2 metadata rebuild task.
 * 
 * Updated for Java 21 to use Virtual Threads for improved concurrency and
 * String Templates for enhanced logging.
 *
 * @since 3.0
 */
@Named
public class RebuildMaven2MetadataTask
    extends RepositoryTaskSupport
    implements Cancelable
{

  private final Type hostedType;

  private final Format maven2Format;

  @Inject
  public RebuildMaven2MetadataTask(@Named(HostedType.NAME) final Type hostedType,
                                   @Named(Maven2Format.NAME) final Format maven2Format)
  {
    this.hostedType = checkNotNull(hostedType);
    this.maven2Format = checkNotNull(maven2Format);
  }


  @Override
  protected void execute(final Repository repository) {
    // Use Virtual Threads for I/O-bound metadata rebuild operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        log.info(STR."Starting Maven metadata rebuild for repository \{repository.getName()} using virtual threads");
        MavenMetadataRebuildFacet mavenHostedFacet = repository.facet(MavenMetadataRebuildFacet.class);
        mavenHostedFacet.rebuildMetadata(
            getConfiguration().getString(GROUPID_FIELD_ID),
            getConfiguration().getString(ARTIFACTID_FIELD_ID),
            getConfiguration().getString(BASEVERSION_FIELD_ID),
            getConfiguration().getBoolean(REBUILD_CHECKSUMS, false),
            getConfiguration().getBoolean(CASCADE_REBUILD, true),
            false
        );
        log.info(STR."Completed Maven metadata rebuild for repository \{repository.getName()}");
      }).get(); // Wait for completion since this is a task
    } catch (Exception e) {
      log.error(STR."Error during Maven metadata rebuild for repository \{repository.getName()}: \{e.getMessage()}", e);
      throw new RuntimeException("Failed to rebuild Maven metadata", e);
    }
  }

  @Override
  protected boolean appliesTo(final Repository repository) {
    return maven2Format.equals(repository.getFormat()) && hostedType.equals(repository.getType());
  }

  @Override
  public String getMessage() {
    // Using Java 21 string template for improved readability and performance
    return STR."Rebuilding Maven Metadata of \{getRepositoryField()}";
  }

}