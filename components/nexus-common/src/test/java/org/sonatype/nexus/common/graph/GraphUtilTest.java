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
package org.sonatype.nexus.common.graph;

import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;

// JUnit 4 imports for Vintage Engine compatibility
import org.junit.Test;

// JUnit 5 imports
import org.junit.jupiter.api.Assertions;

// Hamcrest imports (version 2.2)
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class GraphUtilTest
{
  private static final String ROOT_NODE = "root";

  private static final String TEST_NODE = "test";

  /**
   * Test that depth calculation requires a directed graph.
   */
  @Test(expected = IllegalStateException.class)
  public void shouldThrowExceptionWhenGraphIsUndirected() {
    Graph<String> undirectedGraph = GraphBuilder.undirected().allowsSelfLoops(false).build();

    // JUnit 4 will handle the exception via the expected parameter in @Test annotation
    // For JUnit 5 compatibility, we could use assertThrows, but it would never be reached in JUnit 4 mode
    GraphUtil.depth(undirectedGraph, "", 0);
  }

  /**
   * Test that depth calculation requires a graph without loops.
   */
  @Test(expected = IllegalStateException.class)
  public void shouldThrowExceptionWhenGraphHasLoops() {
    MutableGraph<String> directedGraphWithLoops = GraphBuilder.directed().build();
    directedGraphWithLoops.addNode(ROOT_NODE);
    directedGraphWithLoops.putEdge(ROOT_NODE, TEST_NODE);
    directedGraphWithLoops.putEdge(TEST_NODE, ROOT_NODE);

    // JUnit 4 will handle the exception via the expected parameter in @Test annotation
    // For JUnit 5 compatibility, we could use assertThrows, but it would never be reached in JUnit 4 mode
    GraphUtil.depth(directedGraphWithLoops, "", 0);
  }

  /**
   * Test depth calculation for a node with no child edges.
   */
  @Test
  public void shouldComputeDepthForNodeWithNoChildEdges() {
    MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
    graph.addNode(ROOT_NODE);
    graph.putEdge(ROOT_NODE, TEST_NODE);

    int depth = GraphUtil.depth(graph, TEST_NODE, 0);
    
    // Hamcrest assertion (for backward compatibility)
    assertThat(depth, is(1));
    
    // JUnit 5 assertion
    Assertions.assertEquals(1, depth, "Depth should be 1 for a node with no child edges");
  }

  /**
   * Test depth calculation for a node with multiple levels of child nodes.
   */
  @Test
  public void shouldComputeCorrectDepthWithChildren() {
    MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
    graph.addNode(ROOT_NODE);
    graph.putEdge(ROOT_NODE, TEST_NODE);
    graph.putEdge(TEST_NODE, "A");
    graph.putEdge(TEST_NODE, "B");
    graph.putEdge(TEST_NODE, "C");
    graph.putEdge("A", "AA");
    graph.putEdge("AA", "AAA");
    graph.putEdge("AAA", "AAAA");

    // Test depth from TEST_NODE
    int depthFromTest = GraphUtil.depth(graph, TEST_NODE, 0);
    assertThat(depthFromTest, is(5));
    Assertions.assertEquals(5, depthFromTest, "Depth should be 5 from TEST_NODE");
    
    // Test depth from node A
    int depthFromA = GraphUtil.depth(graph, "A", 0);
    assertThat(depthFromA, is(4));
    Assertions.assertEquals(4, depthFromA, "Depth should be 4 from node A");
  }
}