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
package org.sonatype.nexus.coreui.internal.node;

import java.util.List;
import java.util.Map.Entry;
import java.util.SequencedCollection;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.extdirect.DirectComponentSupport;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;
import static java.lang.StringTemplate.STR;

/**
 * NodeAccessComponent {@link DirectComponentSupport} provides node information for the UI.
 * <p>
 * This component has been updated for Java 21 compatibility with the following enhancements:
 * <ul>
 *   <li>Uses {@link SequencedCollection} instead of List for ordered collections</li>
 *   <li>Implements pattern matching for instanceof checks</li>
 *   <li>Uses String Templates for string formatting</li>
 *   <li>Uses Jakarta EE injection annotations</li>
 * </ul>
 */
@Named
@Singleton
@DirectAction(action = "node_NodeAccess")
public class NodeAccessComponent
    extends DirectComponentSupport
{
  private final NodeAccess nodeAccess;

  /**
   * Constructor with dependency injection.
   * 
   * @param nodeAccess The NodeAccess service to retrieve node information
   */
  @Inject
  public NodeAccessComponent(final NodeAccess nodeAccess) {
    this.nodeAccess = checkNotNull(nodeAccess, STR."NodeAccess cannot be null");
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  public SequencedCollection<NodeInfoXO> nodes() {
    return nodeAccess.getMemberAliases().entrySet().stream().map(this::asNodeInfoXO).collect(toList());
  }

  /**
   * Converts a Map.Entry to a NodeInfoXO using pattern matching.
   * 
   * @param entry The entry containing node ID and display name
   * @return A NodeInfoXO with the node information
   */
  private NodeInfoXO asNodeInfoXO(final Entry<String, String> entry) {
    // Using pattern matching for Entry
    if (entry instanceof Entry<String, String> e) {
      var nodeId = e.getKey();
      var displayName = e.getValue();
      var isLocal = nodeId.equals(nodeAccess.getId());
      
      var nodeInfoXO = new NodeInfoXO();
      nodeInfoXO.setName(nodeId);
      nodeInfoXO.setLocal(isLocal);
      nodeInfoXO.setDisplayName(displayName);
      return nodeInfoXO;
    }
    
    // This should never happen as we're already checking the type in the stream
    throw new IllegalArgumentException(STR."Unexpected entry type: \{entry.getClass().getName()}");
  }
}