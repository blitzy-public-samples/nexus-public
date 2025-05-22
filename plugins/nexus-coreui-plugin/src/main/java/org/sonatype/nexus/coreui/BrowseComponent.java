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
package org.sonatype.nexus.coreui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.encoding.EncodingUtil;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.browse.node.BrowseNode;
import org.sonatype.nexus.repository.browse.node.BrowseNodeConfiguration;
import org.sonatype.nexus.repository.browse.node.BrowseNodeQueryService;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Browse {@link DirectComponent}.
 *
 * @since 3.6
 */
@Named
@Singleton
@DirectAction(action = "coreui_Browse")
public class BrowseComponent
    extends DirectComponentSupport
{
  static final String FOLDER = "folder";

  static final String COMPONENT = "component";

  static final String ASSET = "asset";

  private final BrowseNodeConfiguration configuration;

  private final BrowseNodeQueryService browseNodeQueryService;

  private final RepositoryManager repositoryManager;

  @Inject
  public BrowseComponent(
      final BrowseNodeConfiguration browseNodeConfiguration,
      final BrowseNodeQueryService browseNodeQueryService,
      final RepositoryManager repositoryManager)
  {
    this.configuration = checkNotNull(browseNodeConfiguration);
    this.browseNodeQueryService = checkNotNull(browseNodeQueryService);
    this.repositoryManager = checkNotNull(repositoryManager);
  }

  /**
   * Reads browse nodes using Virtual Threads for improved I/O performance.
   * 
   * @param treeStoreLoadParameters parameters for loading the tree store
   * @return list of browse node transfer objects
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<BrowseNodeXO> read(TreeStoreLoadParameters treeStoreLoadParameters) {
    String repositoryName = treeStoreLoadParameters.getRepositoryName();
    final String path = treeStoreLoadParameters.getNode();

    Repository repository = repositoryManager.get(repositoryName);

    List<String> pathSegments;
    if (isRoot(path)) {
      pathSegments = Collections.emptyList();
    }
    else {
      pathSegments = Arrays.stream(path.split("/"))
          .map(EncodingUtil::urlDecode)
          .collect(Collectors.toList()); // NOSONAR
    }

    // Get browse nodes from the service
    Iterable<BrowseNode> browseNodes = browseNodeQueryService.getByPath(
        repository, pathSegments, configuration.getMaxNodes());
    
    // Use Virtual Threads for processing browse nodes (I/O-bound operations)
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<BrowseNodeXO>> futures = new ArrayList<>();
      
      // Submit each browse node processing task to a virtual thread
      for (BrowseNode browseNode : browseNodes) {
        futures.add(executor.submit(() -> processBrowseNode(browseNode, path)));
      }
      
      // Collect results from all virtual threads
      List<BrowseNodeXO> results = new ArrayList<>(futures.size());
      for (Future<BrowseNodeXO> future : futures) {
        try {
          results.add(future.get());
        } catch (Exception e) {
          log.error("Error processing browse node", e);
        }
      }
      
      return results;
    }
  }

  /**
   * Process a single browse node to create a BrowseNodeXO.
   * This method is executed on a virtual thread for improved concurrency.
   *
   * @param browseNode the browse node to process
   * @param path the current path
   * @return the processed browse node transfer object
   */
  private BrowseNodeXO processBrowseNode(BrowseNode browseNode, String path) {
    String encodedPath = EncodingUtil.urlEncode(browseNode.getName());
    String type = getNodeType(browseNode);
    return new BrowseNodeXO()
        .withId(isRoot(path) ? encodedPath : (path + "/" + encodedPath))
        .withType(type)
        .withText(browseNode.getName())
        .withLeaf(browseNode.isLeaf())
        .withComponentId(browseNode.getComponentId() == null ? null : browseNode.getComponentId().getValue())
        .withAssetId(browseNode.getAssetId() == null ? null : browseNode.getAssetId().getValue())
        .withPackageUrl(browseNode.getPackageUrl());
  }

  public boolean isRoot(String path) {
    return "/".equals(path);
  }

  private String getNodeType(BrowseNode browseNode) {
    if (browseNode.getAssetId() != null) {
      return ASSET;
    }
    else if (browseNode.getComponentId() != null) {
      return COMPONENT;
    }
    else {
      return FOLDER;
    }
  }
}
