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
package org.sonatype.nexus.content.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.importtask.ImportFileConfiguration;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.MavenUploadHandlerSupport;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.MavenPomGenerator;
import org.sonatype.nexus.repository.maven.internal.VersionPolicyValidator;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPayload;

import org.joda.time.DateTime;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.repository.maven.internal.Constants.CHECKSUM_CONTENT_TYPE;
import static org.sonatype.nexus.repository.view.Content.CONTENT_LAST_MODIFIED;

/**
 * Support for uploading maven components via UI & API
 * <p>
 * Java 21 enhancements:
 * - Uses Virtual Threads for I/O-bound operations like checksum file generation
 * - Applies Pattern Matching for type checks in getChecksumsFromContent
 * - Uses String Templates for logging messages
 *
 * @since 3.26
 */
@Named(Maven2Format.NAME)
@Singleton
public class MavenUploadHandler
    extends MavenUploadHandlerSupport
{
  @Inject
  public MavenUploadHandler(
      final Maven2MavenPathParser parser,
      @Named(Maven2Format.NAME) final VariableResolverAdapter variableResolverAdapter,
      final ContentPermissionChecker contentPermissionChecker,
      final VersionPolicyValidator versionPolicyValidator,
      final MavenPomGenerator mavenPomGenerator,
      final Set<UploadDefinitionExtension> uploadDefinitionExtensions)
  {
    super(parser, variableResolverAdapter, contentPermissionChecker, versionPolicyValidator, mavenPomGenerator,
        uploadDefinitionExtensions, true);
  }

  @Override
  protected UploadResponse getUploadResponse(final Repository repository,
                                             final ComponentUpload componentUpload,
                                             final String basePath) throws IOException
  {
    ContentAndAssetPathResponseData responseData =
        createAssets(repository, basePath, componentUpload.getAssetUploads());
    maybeGeneratePom(repository, componentUpload, basePath, responseData);
    updateMetadata(repository, responseData.getCoordinates());
    return new UploadResponse(responseData.getContent(), responseData.getAssetPaths().stream()
        .map(assetPath -> prependIfMissing(assetPath, "/"))
        .collect(toList()));
  }

  /**
   * Updates Maven metadata for the given coordinates using the repository's MavenMetadataRebuildFacet.
   * Uses Java 21 String Templates for logging when coordinates are not available.
   */
  private void updateMetadata(final Repository repository, final Coordinates coordinates) {
    if (coordinates != null) {
      repository.facet(MavenMetadataRebuildFacet.class)
          .rebuildMetadata(coordinates.getGroupId(), coordinates.getArtifactId(), coordinates.getVersion(),
              false, false);
    }
    else {
      log.debug(STR."Not updating metadata.xml files since coordinate could not be retrieved from path");
    }
  }

  /**
   * Puts content from an import file configuration into the repository.
   * Uses Java 21 Virtual Threads for I/O-bound operations to improve performance.
   */
  @Override
  protected Content doPut(final ImportFileConfiguration configuration)
      throws IOException
  {
    Repository repository = configuration.getRepository();
    MavenPath mavenPath = parser.parsePath(configuration.getAssetName());
    File content = configuration.getFile();
    Path contentPath = content.toPath();

    MavenContentFacet contentFacet = repository.facet(MavenContentFacet.class);
    // Use Virtual Thread for probing content type - an I/O operation
    String contentType = Thread.startVirtualThread(() -> {
      try {
        return Files.probeContentType(contentPath);
      } catch (IOException e) {
        throw new RuntimeException(STR."Failed to probe content type for \{contentPath}", e);
      }
    }).join();
    
    try (TempBlob blob = contentFacet.blobs().ingest(contentPath, contentType, MavenPath.HashType.ALGORITHMS,
        configuration.isHardLinkingEnabled())) {
      return doPut(repository, mavenPath, new TempBlobPayload(blob, contentType));
    }
  }

  /**
   * Puts content into the repository and generates checksum files.
   * Delegates to putChecksumFiles which uses Virtual Threads for concurrent processing.
   */
  @Override
  protected Content doPut(final Repository repository, final MavenPath mavenPath, final Payload payload)
      throws IOException
  {
    MavenContentFacet mavenFacet = repository.facet(MavenContentFacet.class);
    Content asset = mavenFacet.put(mavenPath, payload);
    putChecksumFiles(mavenFacet, mavenPath, asset);
    return asset;
  }

  @Override
  protected VersionPolicy getVersionPolicy(final Repository repository) {
    return repository.facet(MavenContentFacet.class).getVersionPolicy();
  }

  /**
   * Creates a temporary blob from the given payload.
   * The underlying implementation may leverage Virtual Threads for I/O operations.
   */
  @Override
  protected TempBlob createTempBlob(final Repository repository, final PartPayload payload) {
    return repository.facet(MavenContentFacet.class).blobs().ingest(payload, MavenPath.HashType.ALGORITHMS);
  }

  /**
   * Puts checksum files for the given content into the repository.
   * Uses Java 21 Virtual Threads for concurrent I/O operations to improve performance.
   */
  private void putChecksumFiles(final MavenContentFacet facet, final MavenPath path, final Content content)
      throws IOException
  {
    DateTime dateTime = content.getAttributes().require(CONTENT_LAST_MODIFIED, DateTime.class);
    Map<String, String> checksums = getChecksumsFromContent(content);
    
    // Use virtual threads to concurrently process and store checksum files
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      // Submit each checksum file creation as a separate virtual thread task
      var futures = checksums.entrySet().stream()
          .map(e -> executor.submit(() -> {
            try {
              Optional<HashAlgorithm> hashAlgorithm = HashAlgorithm.getHashAlgorithm(e.getKey())
                  .filter(HashType.ALGORITHMS::contains);
              if (hashAlgorithm.isPresent()) {
                Content c = new Content(new StringPayload(e.getValue(), CHECKSUM_CONTENT_TYPE));
                c.getAttributes().set(CONTENT_LAST_MODIFIED, dateTime);
                facet.put(path.hash(HashType.valueOf(e.getKey().toUpperCase())), c);
              }
            } catch (IOException ex) {
              throw new RuntimeException(STR."Failed to put checksum file for \{e.getKey()}", ex);
            }
          }))
          .toList();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (Exception e) {
          if (e.getCause() instanceof IOException) {
            throw (IOException) e.getCause();
          }
          throw new IOException(STR."Error processing checksum files: \{e.getMessage()}", e);
        }
      }
    } finally {
      executor.close();
    }
  }

  /**
   * Extracts checksums from content using Java 21 Pattern Matching for instanceof.
   * This approach simplifies the code by eliminating the need for nested Optional operations.
   */
  private Map<String, String> getChecksumsFromContent(final Content content) {
    Object asset = content.getAttributes().get(Asset.class);
    if (asset instanceof Asset assetObj) {
      Optional<AssetBlob> blobOpt = assetObj.blob();
      if (blobOpt.isPresent()) {
        return blobOpt.get().checksums();
      }
    }
    return Collections.emptyMap();
  }
}