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
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link GraphUtil}.
 */
public class GraphUtilTest
{
  private static final String ROOT_NODE = "root";

  private static final String TEST_NODE = "test";

  @Test
  public void depthRequiresADirectedGraph() {
    Graph<String> undirectedGraph = GraphBuilder.undirected().allowsSelfLoops(false).build();

    assertThrows(IllegalStateException.class, () -> {
      GraphUtil.depth(undirectedGraph, "", 0);
    });
  }

  @Test
  public void depthRequiresNoLoops() {
    MutableGraph<String> directedGraphWithLoops = GraphBuilder.directed().build();
    directedGraphWithLoops.addNode(ROOT_NODE);
    directedGraphWithLoops.putEdge(ROOT_NODE, TEST_NODE);
    directedGraphWithLoops.putEdge(TEST_NODE, ROOT_NODE);

    assertThrows(IllegalStateException.class, () -> {
      GraphUtil.depth(directedGraphWithLoops, "", 0);
    });
  }

  @Test
  public void depthComputesTheDepthForNoChildEdges() {
    MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
    graph.addNode(ROOT_NODE);
    graph.putEdge(ROOT_NODE, TEST_NODE);

    assertThat(GraphUtil.depth(graph, TEST_NODE, 0), is(1));
  }

  @Test
  public void depthComputesTheCorrectDepthWithChildren() {
    MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
    graph.addNode(ROOT_NODE);
    graph.putEdge(ROOT_NODE, TEST_NODE);
    graph.putEdge(TEST_NODE, "A");
    graph.putEdge(TEST_NODE, "B");
    graph.putEdge(TEST_NODE, "C");
    graph.putEdge("A", "AA");
    graph.putEdge("AA", "AAA");
    graph.putEdge("AAA", "AAAA");

    assertThat(GraphUtil.depth(graph, TEST_NODE, 0), is(5));
    assertThat(GraphUtil.depth(graph, "A", 0), is(4));
  }

  @Test
  public void depthHandlesEmptyGraph() {
    MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
    graph.addNode(ROOT_NODE);

    assertThat(GraphUtil.depth(graph, ROOT_NODE, 0), is(1));
  }

  @Test
  public void depthHandlesComplexGraphStructures() {
    // Create a more complex graph structure to test depth calculation
    MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
    
    // Add nodes
    graph.addNode(ROOT_NODE);
    graph.addNode(TEST_NODE);
    graph.addNode("A");
    graph.addNode("B");
    graph.addNode("C");
    graph.addNode("D");
    graph.addNode("E");
    
    // Create a diamond pattern
    graph.putEdge(ROOT_NODE, TEST_NODE);
    graph.putEdge(TEST_NODE, "A");
    graph.putEdge(TEST_NODE, "B");
    graph.putEdge("A", "C");
    graph.putEdge("B", "C");
    graph.putEdge("C", "D");
    graph.putEdge("D", "E");
    
    // The depth should be the longest path from TEST_NODE
    assertThat(GraphUtil.depth(graph, TEST_NODE, 0), is(5)); // TEST_NODE -> A/B -> C -> D -> E
    assertThat(GraphUtil.depth(graph, "C", 0), is(3)); // C -> D -> E
  }
}