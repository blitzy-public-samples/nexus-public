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
package org.sonatype.nexus.repository.maven.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenIndexFacet;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategy;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ContentTypes;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.payloads.PathPayload;

import com.google.common.io.Closer;
import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.IndexReader;
import org.apache.maven.index.reader.IndexWriter;
import org.apache.maven.index.reader.Record;
import org.apache.maven.index.reader.Record.Type;
import org.apache.maven.index.reader.RecordCompactor;
import org.apache.maven.index.reader.RecordExpander;
import org.apache.maven.index.reader.ResourceHandler;
import org.apache.maven.index.reader.WritableResourceHandler;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.apache.maven.index.reader.Utils.allGroups;
import static org.apache.maven.index.reader.Utils.descriptor;
import static org.apache.maven.index.reader.Utils.rootGroup;
import static org.apache.maven.index.reader.Utils.rootGroups;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.maven.internal.Constants.INDEX_MAIN_CHUNK_FILE_PATH;
import static org.sonatype.nexus.repository.maven.internal.Constants.INDEX_PROPERTY_FILE_PATH;

/**
 * General logic for Maven index publishing.
 *
 * @since 3.26
 */
public abstract class MavenIndexPublisher extends ComponentSupport
{
  private static final String INDEX_PROPERTY_FILE = "/" + INDEX_PROPERTY_FILE_PATH;

  private static final String INDEX_MAIN_CHUNK_FILE = "/" + INDEX_MAIN_CHUNK_FILE_PATH;

  private static final RecordExpander RECORD_EXPANDER = new RecordExpander();

  protected static final RecordCompactor RECORD_COMPACTOR = new RecordCompactor();

  /**
   * Gets the MavenPathParser for the specified repository.
   */
  protected abstract MavenPathParser getMavenPathParser(final Repository repository);

  /**
   *
   * Returns a ResourceHandler implementation.
   */
  protected abstract WritableResourceHandler getResourceHandler(final Repository repository);

  /**
   * Deletes the asset at the specified path.
   */
  protected abstract boolean delete(Repository repository, String path) throws IOException;

  /**
   * Deletes index files from given repository, returns {@code true} if there was index in repository.
   */
  public boolean unpublishIndexFiles(final Repository repository) throws IOException {
    checkNotNull(repository);
    return delete(repository, INDEX_PROPERTY_FILE)
        && delete(repository, INDEX_MAIN_CHUNK_FILE);
  }

  /**
   * Get group records from the specified repositories.
   */
  protected abstract Iterable<Iterable<Record>> getGroupRecords(
      final List<Repository> repositories,
      final Closer closer) throws IOException;

  protected Iterable<Record> getRecords(final Repository repository, final Closer closer) throws IOException {
    ResourceHandler resourceHandler = closer.register(getResourceHandler(repository));
    IndexReader indexReader = closer.register(new IndexReader(null, resourceHandler));
    ChunkReader chunkReader = closer.register(indexReader.iterator().next());
    return chunkReader.stream()
        .map(RECORD_EXPANDER::apply)
        .filter(new RecordTypeFilter(Type.ARTIFACT_ADD))
        .toList();
  }

  /**
   * Publishes MI index into {@code target}, sourced from repository's own CMA structures.
   */
  public abstract void publishHostedIndex(
      final Repository repository,
      final DuplicateDetectionStrategy<Record> duplicateDetectionStrategy) throws IOException;

  /**
   * Publishes the Maven index into {@code groupRepository}, sourced from {@code leafMembers} repositories.
   */
  public void publishGroupIndex(
      final Repository groupRepository,
      final List<Repository> leafMembers,
      final DuplicateDetectionStrategy<Record> strategy) throws IOException
  {
    List<String> withoutIndex = new ArrayList<>();
    for (Iterator<Repository> ri = leafMembers.iterator(); ri.hasNext(); ) {
      Repository leafMemberRepository = ri.next();
      if (leafMemberRepository.facet(MavenIndexFacet.class).lastPublished() == null) {
        withoutIndex.add(leafMemberRepository.getName());
        ri.remove();
      }
    }
    if (!withoutIndex.isEmpty()) {
      log.info(STR."Following members of group \{groupRepository.getName()} have no index, will not participate in merged index: \{withoutIndex}");
    }
    publishMergedIndex(groupRepository, leafMembers, strategy);
  }

  /**
   * Publishes the Maven index for the specified proxy repository.
   */
  public void publishProxyIndex(
      final Repository repository,
      final Boolean cacheFallback,
      final DuplicateDetectionStrategy<Record> strategy) throws IOException
  {
    if (!prefetchIndexFiles(repository)) {
      if (Boolean.TRUE.equals(cacheFallback)) {
        log.debug("No remote index found... generating partial index from caches");
        publishHostedIndex(repository, strategy);
      }
      else {
        log.debug("No remote index found... nothing to publish");
      }
    }
  }

  /**
   * Publishes MI index into {@code target}, sourced from {@code repositories} repositories.
   * Uses virtual threads for improved I/O throughput.
   */
  private void publishMergedIndex(
      final Repository target,
      final List<Repository> repositories,
      final DuplicateDetectionStrategy<Record> duplicateDetectionStrategy) throws IOException
  {
    checkNotNull(target);
    checkNotNull(repositories);
    Closer closer = Closer.create();
    try (WritableResourceHandler resourceHandler = getResourceHandler(target);
         IndexWriter indexWriter = new IndexWriter(resourceHandler, target.getName(), false);
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Use virtual threads for I/O operations
      var recordsTask = executor.submit(() -> {
        try {
          return getGroupRecords(repositories, closer);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      var records = recordsTask.get();
      var filteredRecords = records.stream()
          .flatMap(Iterable::stream)
          .filter(duplicateDetectionStrategy)
          .toList();
      
      var decoratedRecords = decorate(filteredRecords, target.getName());
      var compactedRecords = decoratedRecords.stream()
          .map(RECORD_COMPACTOR::apply)
          .toList();
      
      indexWriter.writeChunk(compactedRecords.iterator());
    }
    catch (Throwable t) {
      throw closer.rethrow(t);
    }
    finally {
      closer.close();
    }
  }

  /**
   * Returns the {@link Instant} when index of the given repository was last published.
   */
  public Instant lastPublished(final Repository repository) throws IOException {
    checkNotNull(repository);
    try (ResourceHandler resourceHandler = getResourceHandler(repository)) {
      try (IndexReader indexReader = new IndexReader(null, resourceHandler)) {
        return indexReader.getPublishedTimestamp().toInstant();
      }
    }
    catch (IllegalArgumentException e) {
      // thrown by IndexReader when no index found
      log.debug(STR."No index found in \{repository}", e);
      return null;
    }
  }

  /**
   * Prefetch proxy repository index files, if possible. Returns {@code true} if successful. Accepts only maven proxy
   * types. Returns {@code true} if successfully prefetched files (they exist on remote and are locally cached).
   * Uses virtual threads for improved I/O throughput.
   */
  public boolean prefetchIndexFiles(final Repository repository) throws IOException {
    checkNotNull(repository);
    checkArgument(ProxyType.NAME.equals(repository.getType().getValue()));
    MavenPathParser mavenPathParser = getMavenPathParser(repository);
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var propertyFileTask = executor.submit(() -> {
        try {
          return prefetch(repository, INDEX_PROPERTY_FILE, mavenPathParser);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      var chunkFileTask = executor.submit(() -> {
        try {
          return prefetch(repository, INDEX_MAIN_CHUNK_FILE, mavenPathParser);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      return propertyFileTask.get() && chunkFileTask.get();
    }
    catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException(e);
    }
  }

  /**
   * Primes proxy cache with given path and return {@code true} if succeeds. Accepts only maven proxy type.
   */
  private static boolean prefetch(
      final Repository repository,
      final String path, final MavenPathParser mavenPathParser) throws IOException
  {
    MavenPath mavenPath = mavenPathParser.parsePath(path);
    Request getRequest = new Request.Builder()
        .action(GET)
        .path(path)
        .build();
    Context context = new Context(repository, getRequest);
    context.getAttributes().set(MavenPath.class, mavenPath);
    return repository.facet(ProxyFacet.class).get(context) != null;
  }

  /**
   * This method is copied from MI and Plexus related methods, to produce exactly same (possibly buggy) extensions out
   * of a file path, as MI client will attempt to "fix" those.
   */
  protected static String pathExtension(final String path) {
    String filename = path.toLowerCase(Locale.ENGLISH);
    if (filename.endsWith("tar.gz")) {
      return "tar.gz";
    }
    else if (filename.endsWith("tar.bz2")) {
      return "tar.bz2";
    }
    int lastSep = filename.lastIndexOf('/');
    int lastDot;
    if (lastSep < 0) {
      lastDot = filename.lastIndexOf('.');
    }
    else {
      lastDot = filename.substring(lastSep + 1).lastIndexOf('.');
      if (lastDot >= 0) {
        lastDot += lastSep + 1;
      }
    }
    if (lastDot >= 0 && lastDot > lastSep) {
      return filename.substring(lastDot + 1);
    }
    return null;
  }

  /**
   * Method creating decorated {@link Iterable} of records where "decorated" means that special records
   * like descriptor, rootGroups and allGroups are automatically added as first and two last records (where group
   * related ones are being calculated during iterating over returned iterable).
   */
  protected static Iterable<Record> decorate(
      final Iterable<Record> iterable,
      final String repositoryName)
  {
    final TreeSet<String> allGroups = new TreeSet<>();
    final TreeSet<String> rootGroups = new TreeSet<>();
    
    List<Record> result = new ArrayList<>();
    result.add(descriptor(repositoryName));
    
    // Process records and collect group information
    for (Record rec : iterable) {
      if (rec.getType() != Type.DESCRIPTOR && 
          rec.getType() != Type.ALL_GROUPS && 
          rec.getType() != Type.ROOT_GROUPS) {
        final String groupId = rec.get(Record.GROUP_ID);
        if (groupId != null) {
          allGroups.add(groupId);
          rootGroups.add(rootGroup(groupId));
        }
      }
      result.add(rec);
    }
    
    // Add special group records at the end
    result.add(allGroups(allGroups));
    result.add(rootGroups(rootGroups));
    
    return result;
  }

  protected static String determineContentType(final String name) {
    return switch (name) {
      case String s when s.endsWith(".properties") -> ContentTypes.TEXT_PLAIN;
      case String s when s.endsWith(".gz") -> ContentTypes.APPLICATION_GZIP;
      default -> throw new IllegalArgumentException(STR."Unsupported MI index resource: \{name}");
    };
  }

  protected static Payload createPayload(final Path path, final String contentType) throws IOException {
    return new PathPayload(path, contentType);
  }
  
  /**
   * {@link Predicate} that filters {@link Record} based on allowed {@link Type}.
   */
  protected static class RecordTypeFilter
      implements Predicate<Record>
  {
    private final List<Type> allowedTypes;

    public RecordTypeFilter(final Type... allowedTypes) {
      this.allowedTypes = Arrays.asList(allowedTypes);
    }

    @Override
    public boolean test(final Record input) {
      return allowedTypes.contains(input.getType());
    }
  }
}