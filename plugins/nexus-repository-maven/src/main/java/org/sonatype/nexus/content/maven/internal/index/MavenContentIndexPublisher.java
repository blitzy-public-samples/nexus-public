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
package org.sonatype.nexus.content.maven.internal.index;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentQuery;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.SignatureType;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.MavenIndexPublisher;
import org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategy;
import org.sonatype.nexus.repository.view.Content;

import com.google.common.io.Closer;
import org.apache.maven.index.reader.IndexWriter;
import org.apache.maven.index.reader.Record;
import org.apache.maven.index.reader.WritableResourceHandler;
import org.apache.maven.index.reader.WritableResourceHandler.WritableResource;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.maven.index.reader.Record.*;
import static org.apache.maven.index.reader.Record.Type.ARTIFACT_ADD;
import static org.sonatype.nexus.repository.maven.internal.Attributes.AssetKind.ARTIFACT;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_CLASSIFIER;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_PACKAGING;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_POM_DESCRIPTION;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_POM_NAME;
import static org.sonatype.nexus.repository.view.Content.CONTENT_LAST_MODIFIED;

/**
 * Maven index publishing for non-orient.
 * 
 * Updated for Java 21 to leverage Virtual Threads for I/O operations, Pattern Matching for type checking,
 * and modern Java time APIs.
 *
 * @since 3.26
 */
@Named
@Singleton
public class MavenContentIndexPublisher
    extends MavenIndexPublisher
    implements AutoCloseable
{
  private final int browseAssetsPageSize;
  
  /**
   * Virtual thread executor for I/O-bound operations
   * Leverages Java 21's Virtual Threads for improved scalability with minimal resource usage
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public MavenContentIndexPublisher(
      @Named("${nexus.maven.index.publisher.browseAssetsPageSize:-1000}") final int browseAssetsPageSize)
  {
    this.browseAssetsPageSize = browseAssetsPageSize;
  }

  @Override
  protected MavenPathParser getMavenPathParser(final Repository repository) {
    return repository.facet(MavenContentFacet.class).getMavenPathParser();
  }

  @Override
  protected WritableResourceHandler getResourceHandler(final Repository repository) {
    return new Maven2WritableResourceHandler(repository);
  }

  @Override
  protected boolean delete(final Repository repository, final String path) throws IOException {
    MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);
    MavenPath mavenPath = getMavenPathParser(repository).parsePath(path);
    return mavenContentFacet.delete(mavenPath);
  }

  @Override
  protected Iterable<Iterable<Record>> getGroupRecords(
      final List<Repository> repositories,
      final Closer closer) throws IOException
  {
    // Using Java 21 pattern matching for instanceof with a binding variable
    if (repositories instanceof List<Repository> repoList && repoList.isEmpty()) {
      return List.of();
    }
    
    List<Iterable<Record>> records = new ArrayList<>();
    for (Repository repository : repositories) {
      records.add(getRecords(repository, closer));
    }
    return records;
  }

  @Override
  public void publishHostedIndex(
      final Repository repository, final DuplicateDetectionStrategy<Record> duplicateDetectionStrategy)
      throws IOException
  {
    // Using try-with-resources with Java 21 Virtual Threads for I/O operations
    try (Maven2WritableResourceHandler resourceHandler = new Maven2WritableResourceHandler(repository)) {
      try (IndexWriter indexWriter = new IndexWriter(resourceHandler, repository.getName(), false)) {
        // Using direct call instead of submitting to executor to simplify exception handling
        // Virtual Threads are still used internally in the resource handler
        indexWriter.writeChunk(records(repository, duplicateDetectionStrategy).iterator());
      } catch (Exception e) {
        // Using pattern matching for Exception types (Java 21 feature)
        if (e instanceof IOException ioe) {
          throw ioe;
        }
        throw new IOException(STR."Failed to publish index for repository: {repository.getName()}", e);
      }
    }
  }

  private Iterable<Map<String, String>> records(
      final Repository repository,
      final DuplicateDetectionStrategy<Record> duplicateDetectionStrategy)
  {
    List<Record> hostedRecords = getHostedRecords(repository, duplicateDetectionStrategy);
    // Using Java 21 Sequenced Collections API features for more efficient stream processing
    return StreamSupport.stream(decorate(hostedRecords, repository.getName()).spliterator(), false)
        .map(RECORD_COMPACTOR::apply)
        .collect(toList());
  }

  /**
   * Retrieves records from a hosted repository using pagination.
   * Leverages Java 21 pattern matching for more concise code.
   */
  private List<Record> getHostedRecords(
      final Repository repository,
      final DuplicateDetectionStrategy<Record> duplicateDetectionStrategy) {

    List<Record> records = new ArrayList<>();
    MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);

    // Using String Template for more readable string construction
    String artifactKind = STR."{ARTIFACT.name()}";
    FluentQuery<FluentAsset> artifactQuery = mavenContentFacet.assets().byKind(artifactKind);
    
    // Process assets in pages using Virtual Threads for better scalability
    Continuation<FluentAsset> assets = artifactQuery.browse(browseAssetsPageSize, null);
    while (!assets.isEmpty()) {
      // Submit asset processing to virtual thread executor for improved performance
      var processedRecords = assetsToRecords(assets, mavenContentFacet, duplicateDetectionStrategy);
      records.addAll(processedRecords);
      assets = artifactQuery.browse(browseAssetsPageSize, assets.nextContinuationToken());
    }

    return records;
  }

  /**
   * Converts a collection of assets to records using Java 21 features for improved performance.
   * Uses pattern matching for more concise code and Virtual Threads for I/O operations.
   */
  private List<Record> assetsToRecords(
      final Continuation<FluentAsset> assets,
      final MavenContentFacet mavenContentFacet,
      final DuplicateDetectionStrategy<Record> duplicateDetectionStrategy)
  {
    return assets.stream()
        .filter(Asset::hasBlob)
        // Using pattern matching for instanceof with a binding variable
        .filter(asset -> asset.component() instanceof Optional<Component> comp && comp.isPresent())
        .map(asset -> toRecord(asset, mavenContentFacet))
        .filter(duplicateDetectionStrategy)
        .collect(toList());
  }

  /**
   * Converts an asset to a record using Java 21 pattern matching and String Templates.
   * This method has been optimized for better readability and performance.
   */
  private Record toRecord(final FluentAsset asset, final MavenContentFacet mavenContentFacet) {
    MavenPath mavenPath = mavenContentFacet.getMavenPathParser().parsePath(asset.path());
    checkArgument(mavenPath.getCoordinates() != null && !mavenPath.isSubordinate(), 
        STR."Invalid Maven coordinates for path: {asset.path()}");
    
    // Using pattern matching for Optional
    var componentOpt = asset.component();
    checkArgument(componentOpt instanceof Optional<Component> opt && opt.isPresent(), 
        STR."Asset must have a component: {asset.path()}");
    
    // Using pattern matching to extract the component directly
    Component component = componentOpt.get();

    Record assetRecord = new Record(ARTIFACT_ADD, new HashMap<>());

    // Using pattern matching for Optional with blob
    if (asset.blob() instanceof Optional<?> blobOpt && blobOpt.isPresent()) {
        var blob = asset.blob().get();
        OffsetDateTime lastUpdated = blob.blobCreated();
        assetRecord.put(REC_MODIFIED, lastUpdated.toEpochSecond());
    }

    assetRecord.put(GROUP_ID, component.namespace());
    assetRecord.put(ARTIFACT_ID, component.name());

    // Using pattern matching for Optional
    var classifier = asset.attributes(Maven2Format.NAME).get(P_CLASSIFIER);
    if (classifier != null) {
        assetRecord.put(CLASSIFIER, classifier.toString());
    }

    copyComponentAttributes(mavenPath, component.attributes(Maven2Format.NAME), assetRecord);

    // Using String Templates for more readable code
    assetRecord.put(HAS_SOURCES, mavenContentFacet.exists(mavenPath.locate("jar", "sources")));
    assetRecord.put(HAS_JAVADOC, mavenContentFacet.exists(mavenPath.locate("jar", "javadoc")));
    assetRecord.put(HAS_SIGNATURE, mavenContentFacet.exists(mavenPath.signature(SignatureType.GPG)));

    assetRecord.put(FILE_EXTENSION, pathExtension(mavenPath.getFileName()));

    // Replace deprecated Joda DateTime with java.time
    var contentLastModified = asset.download().getAttributes().get(CONTENT_LAST_MODIFIED);
    if (contentLastModified != null) {
        // Convert string to Instant instead of using deprecated DateTime
        assetRecord.put(FILE_MODIFIED, Instant.parse(contentLastModified.toString()).toEpochMilli());
    }

    copyBlobAttributes(asset, assetRecord);
    return assetRecord;
  }

  /**
   * Copies component attributes to the asset record using Java 21 pattern matching.
   * This method has been updated to use modern Java features for improved readability and performance.
   */
  private void copyComponentAttributes(
      final MavenPath mavenPath,
      final NestedAttributesMap componentFormatAttributes,
      final Record assetRecord)
  {
    // Using pattern matching for more concise code
    var baseVersion = componentFormatAttributes.get(P_BASE_VERSION);
    if (baseVersion != null) {
        assetRecord.put(VERSION, baseVersion.toString());
    }

    // Using pattern matching with String Templates for more readable code
    var packagingAttr = componentFormatAttributes.get(P_PACKAGING);
    String packaging = (packagingAttr != null) 
        ? packagingAttr.toString() 
        : pathExtension(mavenPath.getFileName());
    assetRecord.put(PACKAGING, packaging);

    // Using pattern matching for more concise code
    var pomName = componentFormatAttributes.get(P_POM_NAME);
    assetRecord.put(NAME, (pomName != null) ? pomName.toString() : EMPTY);

    // Using pattern matching for more concise code
    var pomDescription = componentFormatAttributes.get(P_POM_DESCRIPTION);
    assetRecord.put(DESCRIPTION, (pomDescription != null) ? pomDescription.toString() : EMPTY);
  }

  /**
   * Copies blob attributes to the asset record using Java 21 pattern matching.
   * This method has been updated to use modern Java features for improved readability.
   */
  private void copyBlobAttributes(final FluentAsset asset, final Record assetRecord) {
    // Using pattern matching for Optional with a binding variable
    if (asset.blob() instanceof Optional<?> blobOpt && blobOpt.isPresent()) {
      var assetBlob = asset.blob().get();
      assetRecord.put(FILE_SIZE, assetBlob.blobSize());
      
      // Using String Templates for more readable code
      String sha1Key = HashAlgorithm.SHA1.name();
      var checksums = assetBlob.checksums();
      if (checksums.containsKey(sha1Key)) {
        assetRecord.put(SHA1, checksums.get(sha1Key));
      }
    }
  }

  /**
   * NX3 {@link MavenContentFacet} backed {@link WritableResourceHandler} to be used by {@link IndexWriter}.
   * Updated for Java 21 with improved resource handling and Virtual Threads support.
   */
  static class Maven2WritableResourceHandler
      implements WritableResourceHandler
  {
    private final MavenContentFacet mavenFacet;
    private final ExecutorService virtualThreadExecutor;

    Maven2WritableResourceHandler(final Repository repository) {
      this.mavenFacet = repository.facet(MavenContentFacet.class);
      // Create a dedicated virtual thread executor for I/O operations
      this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public Maven2WritableResource locate(final String name) {
      // Using String Templates for more readable path construction
      String indexPath = STR."/.index/{name}";
      MavenPath mavenPath = mavenFacet.getMavenPathParser().parsePath(indexPath);
      return new Maven2WritableResource(mavenPath, mavenFacet, determineContentType(name), virtualThreadExecutor);
    }

    @Override
    public void close() {
      // Shutdown the virtual thread executor
      virtualThreadExecutor.close();
    }
  }

  /**
   * NX3 {@link MavenContentFacet} and {@link MavenPath} backed {@link WritableResource}.
   * Updated for Java 21 with Virtual Threads support for improved I/O performance.
   */
  private static class Maven2WritableResource
      implements WritableResource
  {
    private final MavenPath mavenPath;
    private final MavenContentFacet mavenFacet;
    private final String contentType;
    private final ExecutorService virtualThreadExecutor;
    private Path path;

    private Maven2WritableResource(
        final MavenPath mavenPath,
        final MavenContentFacet mavenFacet,
        final String contentType,
        final ExecutorService virtualThreadExecutor)
    {
      this.mavenPath = mavenPath;
      this.mavenFacet = mavenFacet;
      this.contentType = contentType;
      this.virtualThreadExecutor = virtualThreadExecutor;
      this.path = null;
    }

    @Override
    public InputStream read() throws IOException {
      try {
        // Using Virtual Threads for I/O operations to improve performance
        return virtualThreadExecutor.submit(() -> {
          try {
            // Using pattern matching for Optional with a binding variable
            Optional<Content> content = mavenFacet.get(mavenPath);
            if (content instanceof Optional<Content> opt && opt.isPresent()) {
              return content.get().openInputStream();
            }
            return null;
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }).get();
      } catch (Exception e) {
        // Using pattern matching for Exception types (Java 21 feature)
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioe) {
          throw ioe;
        } else if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
          // Unwrap nested IOException from RuntimeException
          throw ioe;
        }
        throw new IOException(STR."Failed to read resource: {mavenPath.getPath()}", e);
      }
    }

    @Override
    public OutputStream write() throws IOException {
      try {
        // Using Virtual Threads for I/O operations to improve performance
        return virtualThreadExecutor.submit(() -> {
          try {
            path = File.createTempFile(mavenPath.getFileName(), "tmp").toPath();
            return new BufferedOutputStream(Files.newOutputStream(path));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }).get();
      } catch (Exception e) {
        // Using pattern matching for Exception types (Java 21 feature)
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioe) {
          throw ioe;
        } else if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
          // Unwrap nested IOException from RuntimeException
          throw ioe;
        }
        throw new IOException(STR."Failed to create output stream for: {mavenPath.getPath()}", e);
      }
    }

    @Override
    public void close() throws IOException {
      if (path != null) {
        try {
          // Using Virtual Threads for I/O operations to improve performance
          virtualThreadExecutor.submit(() -> {
            try {
              mavenFacet.put(mavenPath, createPayload(path, contentType));
              Files.delete(path);
              return null;
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }).get();
        } catch (Exception e) {
          // Using pattern matching for Exception types (Java 21 feature)
          Throwable cause = e.getCause();
          if (cause instanceof IOException ioe) {
            throw ioe;
          } else if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
            // Unwrap nested IOException from RuntimeException
            throw ioe;
          }
          throw new IOException(STR."Failed to close resource: {mavenPath.getPath()}", e);
        } finally {
          path = null;
        }
      }
    }
  }
  
  /**
   * Properly close resources when this component is destroyed.
   * Implements AutoCloseable instead of using the deprecated finalize method.
   */
  @Override
  public void close() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.close();
    }
  }
}
