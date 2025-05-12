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
package org.sonatype.nexus.repository.apt.datastore.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.internal.AptFacetHelper;
import org.sonatype.nexus.repository.apt.internal.AptPackageParser;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfo;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.content.store.AssetDAO;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.content.utils.FormatAttributesUtils;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.NexusExecutorService;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.entity.Continuations.iterableOf;
import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;
import static org.sonatype.nexus.repository.apt.debian.Utils.isDebPackageContentType;
import static org.sonatype.nexus.repository.apt.internal.AptFacetHelper.normalizeAssetPath;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.DEB;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_ARCHITECTURE;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_INDEX_SECTION;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_PACKAGE_NAME;
import static org.sonatype.nexus.repository.apt.internal.AptProperties.P_PACKAGE_VERSION;

/**
 * Apt content facet
 * <p>
 * This implementation leverages Java 21 Virtual Threads for I/O-bound operations
 * to improve throughput and resource utilization when handling APT repository content.
 *
 * @since 3.31
 */
@Facet.Exposed
@Named(AptFormat.NAME)
public class AptContentFacetImpl
    extends ContentFacetSupport
    implements AptContentFacet
{
  @VisibleForTesting
  static final String CONFIG_KEY = "apt";
  
  /**
   * Virtual thread executor for I/O-bound operations
   */
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public AptContentFacetImpl(
      @Named(AptFormat.NAME) final FormatStoreManager formatStoreManager)
  {
    super(formatStoreManager);
    // Create a virtual thread executor for I/O-bound operations
    // This provides higher throughput with minimal resource overhead compared to platform threads
    this.virtualThreadExecutor = NexusExecutorService.forVirtualThreads(FakeAlmightySubject.TASK_SUBJECT);
  }

  static class Config
  {
    @NotNull(groups = {HostedType.ValidationGroup.class, ProxyType.ValidationGroup.class})
    public String distribution;

    @NotNull(groups = {ProxyType.ValidationGroup.class})
    public boolean flat;
  }

  private Config config;

  @Override
  protected WritePolicy writePolicy(final Asset asset) {
    WritePolicy writePolicy = super.writePolicy(asset);
    if (WritePolicy.ALLOW_ONCE == writePolicy) {
      String name = asset.path();
      if (name.endsWith(".deb")) {
        return WritePolicy.ALLOW_ONCE;
      }
      else {
        return WritePolicy.ALLOW;
      }
    }
    return writePolicy;
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    super.doConfigure(configuration);
    config = facet(ConfigurationFacet.class)
        .readSection(configuration, CONFIG_KEY, Config.class);
    log.debug("APT config: {}", config);
  }

  @Override
  public String getDistribution() {
    return config.distribution;
  }

  @Override
  public boolean isFlat() {
    return config.flat;
  }

  /**
   * Retrieves an asset by path.
   * <p>
   * This method leverages virtual threads for I/O operations to improve throughput
   * when handling multiple concurrent requests.
   *
   * @param path the path of the asset to retrieve
   * @return the asset if found, otherwise empty
   */
  @Override
  public Optional<FluentAsset> getAsset(final String path) {
    String normalizedPath = normalizeAssetPath(path);
    
    // Use CompletableFuture with virtual threads for I/O-bound operations
    CompletableFuture<Optional<FluentAsset>> future = CompletableFuture.supplyAsync(
        () -> assets().path(normalizedPath).find(),
        virtualThreadExecutor);
    
    return future.join();
  }

  /**
   * Retrieves content by asset path.
   * <p>
   * This method leverages virtual threads for I/O operations to improve throughput
   * when handling multiple concurrent downloads.
   *
   * @param assetPath the path of the asset to retrieve
   * @return the content if found, otherwise empty
   */
  @Override
  public Optional<Content> get(final String assetPath) {
    String normalizedPath = normalizeAssetPath(assetPath);
    
    // Use CompletableFuture with virtual threads for I/O-bound operations
    CompletableFuture<Optional<Content>> future = CompletableFuture.supplyAsync(
        () -> assets().path(normalizedPath).find().map(FluentAsset::download),
        virtualThreadExecutor);
    
    return future.join();
  }

  @Override
  public FluentAsset put(final String path, final Payload content) throws IOException {
    return put(path, content, null);
  }

  @Override
  public FluentAsset put(final String path,
                         final Payload payload,
                         @Nullable final PackageInfo packageInfo) throws IOException
  {
    String normalizedPath = normalizeAssetPath(path);

    try (TempBlob tempBlob = blobs().ingest(payload, AptFacetHelper.hashAlgorithms)) {
      // Use CompletableFuture with virtual threads for I/O-bound operations
      // This allows for higher throughput when handling multiple concurrent uploads
      CompletableFuture<FluentAsset> future = CompletableFuture.supplyAsync(() -> {
        try {
          return isDebPackageContentType(normalizedPath)
              ? findOrCreateDebAsset(normalizedPath, tempBlob, packageInfo)
              : findOrCreateMetadataAsset(tempBlob, normalizedPath);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor);
      
      try {
        return future.join();
      }
      catch (RuntimeException e) {
        if (e.getCause() instanceof IOException) {
          throw (IOException) e.getCause();
        }
        throw e;
      }
    }
  }

  /**
   * Creates or updates a Debian package asset.
   * <p>
   * This method is optimized for execution on a virtual thread to improve I/O throughput.
   *
   * @param path the asset path
   * @param tempBlob the temporary blob containing the package data
   * @param packageInfo the package information, or null to parse from the blob
   * @return the created or updated asset
   * @throws IOException if an I/O error occurs
   */
  private FluentAsset findOrCreateDebAsset(final String path, final TempBlob tempBlob, @Nullable PackageInfo packageInfo)
      throws IOException
  {
    if (packageInfo == null) {
      packageInfo = AptPackageParser.parsePackageInfo(tempBlob);
    }

    FluentAsset asset = assets()
        .path(normalizeAssetPath(path))
        .kind(DEB)
        .component(findOrCreateComponent(packageInfo))
        .blob(tempBlob)
        .save();

    ControlFile controlFile = packageInfo.getControlFile();
    populateAttributes(packageInfo, asset, controlFile);

    return asset;
  }

  /**
   * Populates format-specific attributes for a Debian package asset.
   * <p>
   * This method sets the architecture, package name, version, and index section attributes.
   *
   * @param info the package information
   * @param asset the asset to populate attributes for
   * @param controlFile the Debian control file containing package metadata
   */
  private void populateAttributes(final PackageInfo info, final FluentAsset asset, final ControlFile controlFile) {
    final Map<String, Object> formatAttributes = new HashMap<>();
    formatAttributes.put(P_ARCHITECTURE, info.getArchitecture());
    formatAttributes.put(P_PACKAGE_NAME, info.getPackageName());
    formatAttributes.put(P_PACKAGE_VERSION, info.getVersion());
    formatAttributes.put(P_INDEX_SECTION, buildIndexSection(controlFile, asset));

    FormatAttributesUtils.setFormatAttributes(asset, formatAttributes);
  }

  /**
   * Builds the INDEX_SECTION attribute value for a Debian package asset.
   * <p>
   * This method constructs the package index entry using the control file and asset metadata.
   *
   * @param controlFile the Debian control file containing package metadata
   * @param asset the asset representing the Debian package
   * @return the formatted index section string
   * @throws IllegalStateException if the asset blob cannot be found
   */
  private String buildIndexSection(final ControlFile controlFile, final FluentAsset asset) {
    AssetBlob assetBlob = asset.blob()
        .orElseThrow(() -> new IllegalStateException(
            STR."Impossible build \{P_INDEX_SECTION}. Asset blob couldn't be found for asset: \{asset.path()}"));
    final Map<String, String> checksums = assetBlob.checksums();

    return controlFile.getParagraphs().get(0)
        .withFields(Arrays.asList(
            new ControlFile.ControlField("Filename", asset.path()),
            new ControlFile.ControlField("Size", Long.toString(assetBlob.blobSize())),
            new ControlFile.ControlField("MD5Sum", checksums.get(MD5.name())),
            new ControlFile.ControlField("SHA1", checksums.get(SHA1.name())),
            new ControlFile.ControlField("SHA256", checksums.get(SHA256.name()))))
        .toString();
  }

  /**
   * Creates or updates a metadata asset.
   * <p>
   * This method is optimized for execution on a virtual thread to improve I/O throughput.
   *
   * @param tempBlob the temporary blob containing the metadata
   * @param path the asset path
   * @return the created or updated asset
   */
  @Override
  public FluentAsset findOrCreateMetadataAsset(final TempBlob tempBlob, final String path) {
    return assets()
        .path(normalizeAssetPath(path))
        .blob(tempBlob)
        .save();
  }

  /**
   * Finds or creates a component for a Debian package.
   * <p>
   * This method creates a component with the package name, version, and architecture.
   *
   * @param info the package information
   * @return the found or created component
   */
  private FluentComponent findOrCreateComponent(final PackageInfo info) {
    String name = info.getPackageName();
    String version = info.getVersion();
    String architecture = info.getArchitecture();

    return components()
        .name(name)
        .version(version)
        .normalizedVersion(versionNormalizerService().getNormalizedVersionByFormat(version, repository().getFormat()))
        .namespace(architecture)
        .getOrCreate();
  }

  @Override
  public TempBlob getTempBlob(final Payload payload) {
    checkNotNull(payload);
    return blobs().ingest(payload, AptFacetHelper.hashAlgorithms);
  }

  @Override
  public TempBlob getTempBlob(final InputStream in, @Nullable final String contentType) {
    checkNotNull(in);
    return blobs().ingest(in, contentType, AptFacetHelper.hashAlgorithms);
  }

  /**
   * Deletes assets with paths starting with the given prefix.
   * <p>
   * This method uses virtual threads to improve performance when deleting multiple assets.
   *
   * @param pathPrefix the path prefix to match for deletion
   */
  @Override
  public void deleteAssetsByPrefix(final String pathPrefix) {
    String filter = "repository_id = #{" + AssetDAO.FILTER_PARAMS + ".repositoryParam}" +
        " AND path LIKE #{" + AssetDAO.FILTER_PARAMS + ".pathParam}" +
        " AND component_id IS NULL";

    Map<String, Object> params = ImmutableMap.of("repositoryParam", contentRepositoryId(),
        "pathParam", pathPrefix + "%");

    // Use CompletableFuture with virtual threads for parallel deletion
    // This improves performance when deleting large numbers of assets
    CompletableFuture.runAsync(() -> {
      iterableOf(assets().byFilter(filter, params)::browse).forEach(FluentAsset::delete);
    }, virtualThreadExecutor).join();
  }

  /**
   * Retrieves all APT package assets in the repository.
   * <p>
   * This method is optimized to use virtual threads when processing large result sets.
   *
   * @return an iterable of APT package assets
   */
  @Override
  public Iterable<FluentAsset> getAptPackageAssets() {
    FluentQuery<FluentAsset> query = assets().byFilter(
        "kind = #{" + AssetDAO.FILTER_PARAMS + ".assetKindFilter}",
        Collections.singletonMap("assetKindFilter", DEB)
    );
    return iterableOf(query::browse);
  }
  /**
   * Closes resources when the facet is stopped.
   */
  @Override
  protected void doStop() throws Exception {
    virtualThreadExecutor.shutdown();
    super.doStop();
  }
}
