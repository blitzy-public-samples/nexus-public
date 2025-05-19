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
package org.sonatype.nexus.repository.content.director;

import java.util.List;
import java.util.Map;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentComponentBuilder;

import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.WRITE_POLICY;

/**
 * Interface for directing content operations between repositories, such as moving or copying components and assets.
 * 
 * <p>Implementations of this interface provide format-specific logic for content operations. With Java 21,
 * implementations should leverage the following features for improved performance and code quality:</p>
 * 
 * <ul>
 *   <li><b>Virtual Threads</b>: Use for I/O-bound operations to improve throughput and reduce resource consumption</li>
 *   <li><b>Pattern Matching</b>: Use for type checking and data extraction to write more concise and type-safe code</li>
 *   <li><b>Thread Safety</b>: Ensure implementations are thread-safe as operations may be executed concurrently with Virtual Threads</li>
 * </ul>
 * 
 * @since 3.24
 */
public interface ContentDirector
{
  /**
   * The calling algorithm only moves the {@code component} and the directly attached {@code assets}.
   *
   * This is a hook that allows format implementers to handle situations where additional work needs to be done before
   * a component gets moved. The caller will provide the {@code component} being moved along with any {@code assets}
   * attached to that {@code component}. The {@code source} and {@code destination} repositories are also provided for
   * context.
   *
   * This hook may be required in situations where there are unattached assets that may also need to be moved or copied.
   * 
   * <p>Implementation Note: This method is I/O-bound when accessing repository storage. Consider using Java 21 Virtual Threads
   * for implementation to improve throughput when processing multiple components concurrently. For example:</p>
   * <pre>
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *   // Submit I/O-bound tasks to the executor
   *   Future&lt;?&gt; future = executor.submit(() -> performIoOperation());
   *   // ... other operations
   *   future.get(); // Wait for completion if needed
   * }
   * </pre>
   */
  default Component beforeMove(
      final Component component,
      final List<? extends Asset> assets,
      final Repository source,
      final Repository destination)
  {
    return component;
  }

  /**
   * This is a hook that allows format implementations to customize how a component is copied.
   *
   * <p>Implementation Note: This method involves I/O operations when copying component data between repositories.
   * Consider using Java 21 Virtual Threads for implementation to improve throughput when copying multiple components
   * concurrently. Pattern Matching can also be used for more concise type checking when handling different component types:</p>
   * <pre>
   * // Example using Pattern Matching for instanceof with component types
   * if (source instanceof MavenComponent mavenComponent) {
   *     // Maven-specific handling with direct access to mavenComponent
   * } else if (source instanceof NpmComponent npmComponent) {
   *     // npm-specific handling
   * }
   * 
   * // Example using Virtual Threads for concurrent blob transfers
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *     // Submit blob transfer tasks to the executor
   *     List&lt;Future&lt;?&gt;&gt; futures = assets.stream()
   *         .map(asset -> executor.submit(() -> transferBlob(asset, destination)))
   *         .toList();
   *     // Wait for all transfers to complete
   *     for (Future&lt;?&gt; future : futures) {
   *         future.get();
   *     }
   * }
   * </pre>
   *
   * @param source the component to copy
   * @param destination the repository to copy the component to
   * @return a reference to the component
   *
   * @since 3.38
   */
  default FluentComponent copyComponent(final Component source, final Repository destination) {
    ContentFacet content = destination.facet(ContentFacet.class);

    FluentComponentBuilder destComponentBuilder = content.components().name(source.name()).namespace(source.namespace())
        .version(source.version());

    source.attributes().forEach(attribute ->
        destComponentBuilder.attributes(attribute.getKey(), attribute.getValue()));

    return destComponentBuilder.getOrCreate();
  }

  /**
   * This is a hook that allows format implementors to handle situations where additional work is required after each
   * individual {@code component} is moved to the {@code destination} repository.
   *
   * One example of this is when some repository-spanning metadata needs to be updated after an
   * individual component is moved.
   * 
   * <p>Implementation Note: This method often involves I/O operations for metadata updates. Consider using Java 21
   * Virtual Threads for implementation to improve throughput when processing metadata updates concurrently. Also,
   * ensure thread safety when updating shared metadata:</p>
   * <pre>
   * // Example using Virtual Threads for metadata updates
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *     executor.submit(() -> updateMetadata(component, destination));
   * }
   * 
   * // Example using Pattern Matching for switch to handle different component types
   * Object componentType = determineComponentType(component);
   * String metadataPath = switch(componentType) {
   *     case MavenType m -> "maven-metadata.xml";
   *     case NpmType n when n.isScoped() -> "package.json";
   *     case NpmType n -> n.getName() + "/package.json";
   *     default -> throw new IllegalArgumentException("Unsupported component type");
   * };
   * </pre>
   */
  default Component afterMove(final Component component, final Repository destination) {
    return component;
  }

  /**
   * This is a hook that allows format implementors to decide if a given {@code destination} repository is an
   * acceptable target for a move operation.
   */
  default boolean allowMoveTo(final Repository destination) {
    return false;
  }

  /**
   * This is a hook that allows format implementors to decide if a given {@code component} is allowed to be moved to
   * the {@code destination} repository.
   */
  default boolean allowMoveTo(final FluentComponent component, final Repository destination) {
    return false;
  }

  /**
   * This is a hook that allows format implementors to decide if a given {@code source} repository is an
   * acceptable source for a move operation.
   */
  default boolean allowMoveFrom(final Repository source) {
    return false;
  }

  /**
   * This is a hook that allows format implementors to handle situations where additional work is required all of
   * the {@code components} have been moved to the {@code destination} repository. Each component is represented by
   * a map that contains entries for the group, name, and version if they are available.
   *
   * One example of this is when some repository-spanning metadata can be updated for a set of components after
   * they are moved.
   * 
   * <p>Implementation Note: This method typically involves I/O-bound operations for updating repository metadata.
   * Consider using Java 21 Virtual Threads for implementation to improve throughput when processing metadata updates
   * concurrently. Ensure proper synchronization when updating shared metadata files:</p>
   * <pre>
   * // Example using Virtual Threads for concurrent metadata processing
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *     // Group components by their metadata file to avoid concurrent updates to the same file
   *     Map&lt;String, List&lt;Map&lt;String, String&gt;&gt;&gt; groupedComponents = components.stream()
   *         .collect(Collectors.groupingBy(this::getMetadataPath));
   *     
   *     // Process each group of components that share a metadata file
   *     List&lt;Future&lt;?&gt;&gt; futures = groupedComponents.entrySet().stream()
   *         .map(entry -> executor.submit(() -> updateMetadataForGroup(entry.getKey(), entry.getValue(), destination)))
   *         .toList();
   *     
   *     // Wait for all metadata updates to complete
   *     for (Future&lt;?&gt; future : futures) {
   *         future.get();
   *     }
   * }
   * </pre>
   */
  default void afterMove(final List<Map<String, String>> components, final Repository destination) {
    // no-op
  }

  /**
   * Check whether redeploy is allowed for the specified Component, implementers need only override this method
   * for formats which allow re-writes in some scenarios.
   */
  default boolean redeployAllowed(final Repository destination, final Component component) {
    return WritePolicy.ALLOW.name().equals(destination.getConfiguration().attributes(STORAGE).get(WRITE_POLICY));
  }
}