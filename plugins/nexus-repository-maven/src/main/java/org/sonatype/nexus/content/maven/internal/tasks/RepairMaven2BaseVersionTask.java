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
package org.sonatype.nexus.content.maven.internal.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Named;

import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.store.Maven2ComponentData;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.scheduling.Cancelable;

/**
 * Task to repair Maven 2 base versions in components where they might be missing.
 * <p>
 * This implementation leverages Java 21 Virtual Threads for improved performance
 * when processing multiple components concurrently. Virtual Threads are lightweight
 * threads that are particularly well-suited for I/O-bound operations like database updates.
 * </p>
 *
 * @since 3.0
 */
@Named
public class RepairMaven2BaseVersionTask
    extends RepositoryTaskSupport
    implements Cancelable
{
  @Override
  protected void execute(final Repository repository) {
    MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);
    Iterable<FluentComponent> componentsWithMissedBaseVersion = mavenContentFacet.getComponentsWithMissedBaseVersion();
    
    // Create a list to hold all components for processing
    List<FluentComponent> componentList = new ArrayList<>();
    componentsWithMissedBaseVersion.forEach(componentList::add);
    
    if (componentList.isEmpty()) {
      log.info("No components with missed base version found in repository {}", getRepositoryField());
      return;
    }
    
    log.info("Found {} components with missed base version in repository {}", componentList.size(), getRepositoryField());
    
    // Use Java 21 Virtual Threads for concurrent processing of components
    // Virtual Threads are ideal for I/O-bound operations like database updates
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (FluentComponent fluentComponent : componentList) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            Maven2ComponentData componentData = new Maven2ComponentData();
            componentData.setNamespace(fluentComponent.namespace());
            componentData.setName(fluentComponent.name());
            componentData.setVersion(fluentComponent.version());
            componentData.setRepositoryId(mavenContentFacet.contentRepositoryId());
            NestedAttributesMap maven2 = fluentComponent.attributes("maven2");
            componentData.setBaseVersion(maven2.get("baseVersion", String.class));
            mavenContentFacet.updateBaseVersion(componentData);
            log.debug("Updated base version for component: {}:{}", componentData.getNamespace(), componentData.getName());
          } catch (Exception e) {
            log.error("Failed to update base version for component: {}:{}", 
                fluentComponent.namespace(), fluentComponent.name(), e);
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      log.info("Completed base version repair for {} components in repository {}", 
          componentList.size(), getRepositoryField());
    }
  }

  @Override
  protected boolean appliesTo(final Repository repository) {
    return repository.getFormat().getValue().equals(Maven2Format.NAME);
  }

  @Override
  public String getMessage() {
    // Using Java 21 String Template for improved readability
    return STR."Fixed Maven Base Versions of \{getRepositoryField()}";
  }
}