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
package org.sonatype.nexus.content.maven.internal.browse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;
import org.sonatype.nexus.repository.browse.node.BrowsePath;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;

/**
 * Tests for {@link Maven2BrowseNodeGenerator} with Java 21 features.
 * 
 * @since 3.60
 */
@DisplayName("Maven2BrowseNodeGenerator Tests")
public class Maven2BrowseNodeGeneratorTest
    extends TestSupport
{
  private static final String BASE_VERSION = "1.3";

  private static final String TIMESTAMPED_VERSION = "1.3-20200717.093520-1";

  private static final String SNAPSHOT_VERSION = "1.3-SNAPSHOT";

  private Maven2BrowseNodeGenerator underTest = new Maven2BrowseNodeGenerator();

  @Test
  @DisplayName("Build paths to base versioned asset with component")
  public void should_build_paths_to_base_versioned_asset_which_has_a_component() {
    AssetData asset = new AssetData();
    asset.setPath("/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar");
    asset.setComponent(aReleaseVersionedComponent());

    List<BrowsePath> browsePaths = underTest.computeAssetPaths(asset);

    assertThat(browsePaths.size(), is(5));
    assertThat(browsePaths, containsInAnyOrder(
        new BrowsePath("org", "/org/"),
        new BrowsePath("hamcrest", "/org/hamcrest/"),
        new BrowsePath("hamcrest-core", "/org/hamcrest/hamcrest-core/"),
        new BrowsePath(BASE_VERSION, "/org/hamcrest/hamcrest-core/1.3/"),
        new BrowsePath("hamcrest-core-1.3.jar", asset.path())));
  }

  @Test
  @DisplayName("Build paths to timestamped versioned asset with component")
  public void should_build_paths_to_timestamped_versioned_asset_which_has_a_component() {
    AssetData asset = new AssetData();
    asset.setPath("/org/hamcrest/hamcrest-core/1.3-SNAPSHOT/hamcrest-core-1.3-20200717.093520-1.jar");
    asset.setComponent(aSnapshotVersionedComponent());

    List<BrowsePath> browsePaths = underTest.computeAssetPaths(asset);

    assertThat(browsePaths.size(), is(6));
    assertThat(browsePaths, containsInAnyOrder(
        new BrowsePath("org", "/org/"),
        new BrowsePath("hamcrest", "/org/hamcrest/"),
        new BrowsePath("hamcrest-core", "/org/hamcrest/hamcrest-core/"),
        new BrowsePath(SNAPSHOT_VERSION, "/org/hamcrest/hamcrest-core/1.3-SNAPSHOT/"),
        new BrowsePath(TIMESTAMPED_VERSION,
            "/org/hamcrest/hamcrest-core/1.3-SNAPSHOT/1.3-20200717.093520-1/"),
        new BrowsePath("hamcrest-core-1.3-20200717.093520-1.jar", asset.path())));
  }

  @Test
  @DisplayName("Build paths for asset without a component")
  public void should_build_paths_for_asset_without_a_component() {
    AssetData asset = new AssetData();
    asset.setPath("/com/sonatype/example/metadata.xml");

    List<BrowsePath> browsePaths = underTest.computeAssetPaths(asset);

    assertThat(browsePaths.size(), is(4));
    assertThat(browsePaths, containsInAnyOrder(
        new BrowsePath("com", "/com/"),
        new BrowsePath("sonatype", "/com/sonatype/"),
        new BrowsePath("example", "/com/sonatype/example/"),
        new BrowsePath("metadata.xml", asset.path())));
  }

  @Test
  @DisplayName("Build paths to base versioned component")
  public void should_build_paths_to_base_versioned_component() {
    AssetData asset = new AssetData();
    asset.setComponent(aReleaseVersionedComponent());

    List<BrowsePath> browsePaths = underTest.computeComponentPaths(asset);

    assertThat(browsePaths.size(), is(4));
    assertThat(browsePaths, containsInAnyOrder(
        new BrowsePath("org", "/org/"),
        new BrowsePath("hamcrest", "/org/hamcrest/"),
        new BrowsePath("hamcrest-core", "/org/hamcrest/hamcrest-core/"),
        new BrowsePath(BASE_VERSION, "/org/hamcrest/hamcrest-core/1.3/")));
  }

  @Test
  @DisplayName("Build paths to timestamped versioned component")
  public void should_build_paths_to_timestamped_versioned_component() {
    AssetData asset = new AssetData();
    asset.setComponent(aSnapshotVersionedComponent());

    List<BrowsePath> browsePaths = underTest.computeComponentPaths(asset);

    assertThat(browsePaths.size(), is(5));
    assertThat(browsePaths, containsInAnyOrder(
        new BrowsePath("org", "/org/"),
        new BrowsePath("hamcrest", "/org/hamcrest/"),
        new BrowsePath("hamcrest-core", "/org/hamcrest/hamcrest-core/"),
        new BrowsePath(SNAPSHOT_VERSION, "/org/hamcrest/hamcrest-core/1.3-SNAPSHOT/"),
        new BrowsePath(TIMESTAMPED_VERSION,
            "/org/hamcrest/hamcrest-core/1.3-SNAPSHOT/1.3-20200717.093520-1/")));
  }

  @Test
  @DisplayName("Build paths to component without a namespace")
  public void should_build_paths_to_component_without_a_namespace() {
    AssetData asset = new AssetData();
    asset.setComponent(aComponentWithNoNamespace());

    List<BrowsePath> browsePaths = underTest.computeComponentPaths(asset);

    assertThat(browsePaths.size(), is(2));
    assertThat(browsePaths, containsInAnyOrder(
        new BrowsePath("hamcrest-core", "/hamcrest-core/"),
        new BrowsePath("1.3", "/hamcrest-core/1.3/")));
  }

  @Test
  @DisplayName("Build paths to component with name only")
  public void should_build_paths_to_component_with_name_only() {
    AssetData asset = new AssetData();
    asset.setComponent(aComponentWithNameOnly());

    List<BrowsePath> browsePaths = underTest.computeComponentPaths(asset);

    assertThat(browsePaths.size(), is(1));
    assertThat(browsePaths, containsInAnyOrder(new BrowsePath("hamcrest-core", "/hamcrest-core/")));
  }

  /**
   * Tests for Java 21 features like pattern matching for switch and record patterns.
   */
  @Nested
  @DisplayName("Java 21 Pattern Matching Tests")
  class PatternMatchingTests {
    
    /**
     * Test demonstrating pattern matching for switch with component types.
     */
    @Test
    @DisplayName("Pattern matching for switch with component types")
    @Tag("Java21")
    public void should_use_pattern_matching_for_switch() {
      // Create test components
      Component releaseComponent = aReleaseVersionedComponent();
      Component snapshotComponent = aSnapshotVersionedComponent();
      Component noNamespaceComponent = aComponentWithNoNamespace();
      Component nameOnlyComponent = aComponentWithNameOnly();
      
      // Test pattern matching for switch with different component types
      String result = getComponentTypeDescription(releaseComponent);
      assertThat(result, is("Release component with version " + BASE_VERSION));
      
      result = getComponentTypeDescription(snapshotComponent);
      assertThat(result, is("Snapshot component with version " + SNAPSHOT_VERSION));
      
      result = getComponentTypeDescription(noNamespaceComponent);
      assertThat(result, is("Component without namespace: hamcrest-core"));
      
      result = getComponentTypeDescription(nameOnlyComponent);
      assertThat(result, is("Component with name only: hamcrest-core"));
    }
    
    /**
     * Helper method that uses pattern matching for switch to determine component type.
     * Demonstrates Java 21 pattern matching capabilities.
     */
    private String getComponentTypeDescription(Component component) {
      return switch (component) {
        case ComponentData c when !c.namespace().isEmpty() && c.attributes().child(Maven2Format.NAME).get(BASE_VERSION, String.class).contains("SNAPSHOT") ->
            "Snapshot component with version " + c.attributes().child(Maven2Format.NAME).get(BASE_VERSION, String.class);
        case ComponentData c when !c.namespace().isEmpty() ->
            "Release component with version " + c.attributes().child(Maven2Format.NAME).get(BASE_VERSION, String.class);
        case ComponentData c when c.namespace().isEmpty() && !c.version().isEmpty() ->
            "Component without namespace: " + c.name();
        case ComponentData c when c.namespace().isEmpty() && c.version().isEmpty() ->
            "Component with name only: " + c.name();
        default -> "Unknown component type";
      };
    }
    
    /**
     * Test demonstrating record patterns with component data.
     * This is a simplified example as ComponentData is not a record,
     * but shows how record patterns would be used.
     */
    @Test
    @DisplayName("Using record patterns with component data")
    @Tag("Java21")
    public void should_demonstrate_record_pattern_usage() {
      // Create a record to represent component data for demonstration purposes
      record ComponentInfo(String name, String namespace, String version) {}
      
      // Create test component
      Component component = aReleaseVersionedComponent();
      
      // Create a ComponentInfo from the component
      ComponentInfo info = new ComponentInfo(
          component.name(),
          component.namespace(),
          component.version());
      
      // Use pattern matching with the record
      if (info instanceof ComponentInfo(String name, String namespace, String version)) {
        assertThat(name, is("hamcrest-core"));
        assertThat(namespace, is("org.hamcrest"));
        assertThat(version, is(BASE_VERSION));
      }
    }
  }
  
  /**
   * Tests for Java 21 virtual threads with browse node generation.
   */
  @Nested
  @DisplayName("Java 21 Virtual Threads Tests")
  @Execution(ExecutionMode.CONCURRENT)
  class VirtualThreadsTests {
    
    /**
     * Test demonstrating virtual threads for concurrent browse path generation.
     */
    @Test
    @DisplayName("Generate browse paths concurrently with virtual threads")
    @Tag("Java21")
    public void should_generate_browse_paths_with_virtual_threads() throws Exception {
      // Number of concurrent operations
      int concurrentOperations = 100;
      CountDownLatch latch = new CountDownLatch(concurrentOperations);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Create a virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit tasks to generate browse paths concurrently
        for (int i = 0; i < concurrentOperations; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Create asset with component
              AssetData asset = new AssetData();
              asset.setPath("/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3-" + index + ".jar");
              asset.setComponent(aReleaseVersionedComponent());
              
              // Generate browse paths
              List<BrowsePath> paths = underTest.computeAssetPaths(asset);
              
              // Verify paths were generated correctly
              if (paths.size() == 5) {
                successCount.incrementAndGet();
              }
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete
        latch.await(5, TimeUnit.SECONDS);
      }
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), is(concurrentOperations));
    }
    
    /**
     * Test comparing performance of platform threads vs virtual threads.
     * This is a simple benchmark to demonstrate the efficiency of virtual threads.
     */
    @Test
    @DisplayName("Compare platform threads vs virtual threads performance")
    @Tag("Java21")
    @Tag("Performance")
    public void should_compare_thread_performance() throws Exception {
      // Skip detailed performance test in regular test runs
      if (!Boolean.getBoolean("run.performance.tests")) {
        return;
      }
      
      int operations = 1000;
      
      // Test with platform threads
      long platformThreadTime = measureExecutionTime(() -> {
        try (ExecutorService executor = Executors.newFixedThreadPool(100)) {
          runConcurrentBrowsePathGeneration(executor, operations);
        }
      });
      
      // Test with virtual threads
      long virtualThreadTime = measureExecutionTime(() -> {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          runConcurrentBrowsePathGeneration(executor, operations);
        }
      });
      
      log.info("Platform threads execution time: {} ms", platformThreadTime);
      log.info("Virtual threads execution time: {} ms", virtualThreadTime);
      log.info("Performance improvement: {}%", 
          platformThreadTime > 0 ? (platformThreadTime - virtualThreadTime) * 100 / platformThreadTime : 0);
    }
    
    private void runConcurrentBrowsePathGeneration(ExecutorService executor, int operations) throws Exception {
      CountDownLatch latch = new CountDownLatch(operations);
      
      for (int i = 0; i < operations; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            AssetData asset = new AssetData();
            asset.setPath("/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3-" + index + ".jar");
            asset.setComponent(aReleaseVersionedComponent());
            underTest.computeAssetPaths(asset);
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(10, TimeUnit.SECONDS);
    }
    
    private long measureExecutionTime(Runnable task) throws Exception {
      long startTime = System.currentTimeMillis();
      task.run();
      return System.currentTimeMillis() - startTime;
    }
  }
  
  /**
   * Tests for Java 21 string templates with browse node generation.
   */
  @Nested
  @DisplayName("Java 21 String Templates Tests")
  class StringTemplatesTests {
    
    /**
     * Test demonstrating string templates for browse path formatting.
     */
    @Test
    @DisplayName("Format browse paths using string templates")
    @Tag("Java21")
    public void should_format_browse_paths_with_string_templates() {
      // Create test component and asset
      Component component = aReleaseVersionedComponent();
      AssetData asset = new AssetData();
      asset.setPath("/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar");
      asset.setComponent(component);
      
      // Generate browse paths
      List<BrowsePath> browsePaths = underTest.computeAssetPaths(asset);
      
      // Format paths using string templates
      String formattedPaths = formatBrowsePathsWithTemplates(browsePaths, component);
      
      // Verify the formatted output contains expected information
      assertThat(formattedPaths.contains("Component: hamcrest-core"), is(true));
      assertThat(formattedPaths.contains("Version: 1.3"), is(true));
      assertThat(formattedPaths.contains("Path: /org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"), is(true));
    }
    
    /**
     * Helper method that uses string templates to format browse paths.
     * Demonstrates Java 21 string template capabilities.
     */
    private String formatBrowsePathsWithTemplates(List<BrowsePath> paths, Component component) {
      StringBuilder result = new StringBuilder();
      
      // Using string concatenation to simulate string templates since they're a preview feature
      // In Java 21 with preview features enabled, this would use actual string templates:
      // String header = STR."Component: \{component.name()}\nVersion: \{component.version()}\n";
      String header = "Component: " + component.name() + "\nVersion: " + component.version() + "\n";
      result.append(header);
      
      result.append("Browse Paths:\n");
      for (BrowsePath path : paths) {
        // In Java 21 with preview features enabled:
        // String pathInfo = STR."  - \{path.displayName()} -> \{path.requestPath()}";
        String pathInfo = "  - " + path.displayName() + " -> " + path.requestPath();
        result.append(pathInfo).append("\n");
      }
      
      // In Java 21 with preview features enabled:
      // String footer = STR."Path: \{paths.get(paths.size() - 1).requestPath()}";
      String footer = "Path: " + paths.get(paths.size() - 1).requestPath();
      result.append(footer);
      
      return result.toString();
    }
  }

  private Component aReleaseVersionedComponent() {
    ComponentData componentData = createComponent();
    componentData.setVersion(BASE_VERSION);
    formatAttributes(componentData, BASE_VERSION);
    return componentData;
  }

  private ComponentData createComponent() {
    ComponentData componentData = new ComponentData();
    componentData.setRepositoryId(1);
    componentData.setComponentId(1);
    componentData.setName("hamcrest-core");
    componentData.setNamespace("org.hamcrest");
    return componentData;
  }

  private void formatAttributes(final ComponentData componentData, final String baseVersion) {
    Map<String, String> formatAttributes = new HashMap<>();
    formatAttributes.put(Maven2BrowseNodeGenerator.BASE_VERSION, baseVersion);
    componentData.attributes().set(Maven2Format.NAME, formatAttributes);
  }

  private ComponentData aSnapshotVersionedComponent() {
    ComponentData componentData = createComponent();
    componentData.setVersion(TIMESTAMPED_VERSION);
    formatAttributes(componentData, SNAPSHOT_VERSION);
    return componentData;
  }

  private Component aComponentWithNoNamespace() {
    ComponentData componentData = createComponent();
    componentData.setNamespace(EMPTY);
    componentData.setVersion(BASE_VERSION);
    formatAttributes(componentData, BASE_VERSION);
    return componentData;
  }

  private Component aComponentWithNameOnly() {
    ComponentData componentData = createComponent();
    componentData.setNamespace(EMPTY);
    componentData.setVersion(EMPTY);
    formatAttributes(componentData, BASE_VERSION);
    return componentData;
  }
}