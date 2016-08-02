package com.google.common.html.plugin.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.html.plugin.Sources;
import com.google.common.html.plugin.Sources.Source;
import com.google.common.html.plugin.plan.Hash;
import com.google.common.html.plugin.plan.Ingredient;
import com.google.common.html.plugin.plan.PlanKey;

/**
 * Pools ingredients based on key.
 */
public class Ingredients {

  private final Cache<PlanKey, Ingredient> ingredients =
      CacheBuilder.newBuilder()
      // TODO: is this right?
      .weakValues()
      .build();

  /**
   * Lazily allocate an ingredient with the given key based on a spec.
   */
  public <T extends Ingredient>
  T get(Class<T> type, PlanKey key, final Supplier<? extends T> maker) {
    Ingredient got;
    try {
      got = ingredients.get(key, new Callable<T>() {
        @Override
        public T call() {
          return maker.get();
        }
      });
    } catch (ExecutionException ex) {
      throw (AssertionError) new AssertionError().initCause(ex);
    }
    Preconditions.checkState(key.equals(got.key));
    return type.cast(got);
  }

  private static Source singletonSource(File file) throws IOException {
    File canonFile = file.getCanonicalFile();

    class RootFinder {
      File root = null;
      File deroot(File f) {
        File parent = f.getParentFile();
        if (parent == null) {
          root = f;
          return null;
        } else {
          return new File(deroot(parent), f.getName());
        }
      }
    }

    RootFinder rf = new RootFinder();
    File relFile = rf.deroot(file);
    if (relFile == null) {
      throw new IOException("The file-system root cannot be a source file");
    }
    return new Sources.Source(canonFile, rf.root, relFile);
  }

  /** An ingredient backed by a file which is hashable when the file exists. */
  public FileIngredient file(File file) throws IOException {
    return file(singletonSource(file));
  }

  /** An ingredient backed by a file which is hashable when the file exists. */
  public FileIngredient file(final Source source) {
    final PlanKey key = PlanKey.builder("file")
        .addString(source.canonicalPath.getPath())
        .build();
    return get(
        FileIngredient.class,
        key,
        new Supplier<FileIngredient>() {
          @SuppressWarnings("synthetic-access")
          @Override
          public FileIngredient get() {
            return new FileIngredient(key, source);
          }
        });
  }


  /**
   * A set of files whose hash is the file paths, not their content, and
   * which is hashable when explicitly resolved.
   */
  @SuppressWarnings("synthetic-access")
  public DirScanFileSetIngredient fileset(Sources.Finder finder) {
    final Sources.Finder finderCopy = finder.clone();

    List<String> mainRootStrings = Lists.newArrayList();
    for (File f : finderCopy.mainRoots()) {
      mainRootStrings.add(f.getPath());
    }
    Collections.sort(mainRootStrings);
    List<String> testRootStrings = Lists.newArrayList();
    for (File f : finderCopy.testRoots()) {
      testRootStrings.add(f.getPath());
    }
    Collections.sort(testRootStrings);

    List<String> exclusionStrings = Lists.newArrayList();
    for (PathGlob exclusion : finder.exclusions()) {
      exclusionStrings.add(exclusion.getGlobString());
    }
    Collections.sort(testRootStrings);

    final PlanKey key = PlanKey.builder("fileset")
        .addString(finderCopy.suffixPattern().pattern())
        .addStrings(mainRootStrings)
        .addStrings(testRootStrings)
        .addStrings(exclusionStrings)
        .build();

    return get(
        DirScanFileSetIngredient.class,
        key,
        new Supplier<DirScanFileSetIngredient>() {
          @Override
          public DirScanFileSetIngredient get() {
            return new DirScanFileSetIngredient(key, finderCopy);
          }
        });
  }

  /**
   * A file-set that has a name but whose content is derived from some
   * computation that is not itself hashable.
   */
  public SettableFileSetIngredient namedFileSet(String name) {
    final PlanKey key = PlanKey.builder("named-files").addString(name).build();
    return get(
        SettableFileSetIngredient.class,
        key,
        new Supplier<SettableFileSetIngredient>() {
          @Override
          public SettableFileSetIngredient get() {
            return new SettableFileSetIngredient(key);
          }
        });
  }


  /** An ingredient that represents a fixed string. */
  public StringValue stringValue(final String s) {
    final PlanKey key = PlanKey.builder("str").addString(s).build();
    return get(
        StringValue.class,
        key,
        new Supplier<StringValue>() {
          @Override
          public StringValue get() {
            return new StringValue(key, s);
          }
        });

  }

  /**
   * An ingredient that represents a path, and hashes to a hash of that
   * path, not the content of the file referred to by that path.
   */
  public PathValue pathValue(final File f) {
    final PlanKey key = PlanKey.builder("path").addString(f.getPath()).build();
    return get(
        PathValue.class,
        key,
        new Supplier<PathValue>() {
          @Override
          public PathValue get() {
            return new PathValue(key, f);
          }
        });
  }

  /**
   * An ingredient that represents a path, and hashes to a hash of that
   * path, not the content of the file referred to by that path.
   */
  public UriValue uriValue(final URI uri) {
    final PlanKey key = PlanKey.builder("uri")
        .addString(uri.toASCIIString())
        .build();
    return get(
        UriValue.class,
        key,
        new Supplier<UriValue>() {
          @Override
          public UriValue get() {
            return new UriValue(key, uri);
          }
        });
  }

  /**
   * Specifies how the compiler should interpret a group of source files and
   * where to look for those source files.
   */
  public <T extends Options> OptionsIngredient<T> options(
      Class<T> optionsType, final T options) {
    final PlanKey key = Preconditions.checkNotNull(options.getKey());
    OptionsIngredient<?> ing = get(
        OptionsIngredient.class,
        key,
        new Supplier<OptionsIngredient<T>>() {
          @Override
          public OptionsIngredient<T> get() {
            return new OptionsIngredient<>(key, options);
          }
        });
    return ing.asSuperType(optionsType);
  }

  /**
   * An ingredient back by a file dedicated to hold a serialized object of
   * a specific type.  Reading and writing must be done explicitly and the
   * hash is of the version in memory.
   */
  public <T extends Serializable>
  SerializedObjectIngredient<T> serializedObject(
      File file, Class<T> contentType)
  throws IOException {
    return serializedObject(singletonSource(file), contentType);
  }

  /**
   * An ingredient back by a file dedicated to hold a serialized object of
   * a specific type.  Reading and writing must be done explicitly and the
   * hash is of the version in memory.
   */
  public <T extends Serializable>
  SerializedObjectIngredient<T> serializedObject(
      final Source source, final Class<T> contentType) {
    final PlanKey key = PlanKey.builder("file")
        .addString(source.canonicalPath.getPath())
        .build();
    SerializedObjectIngredient<?> ing = get(
        SerializedObjectIngredient.class, key,
        new Supplier<SerializedObjectIngredient<T>>() {
          @Override
          public SerializedObjectIngredient<T> get() {
            return new SerializedObjectIngredient<>(key, source, contentType);
          }
        });
    return ing.asSuperType(contentType);
  }

  /**
   * Group a bunch of related ingredients together.
   */
  public <I extends Ingredient> Bundle<I> bundle(Iterable<? extends I> ings) {
    final ImmutableList<I> ingList = ImmutableList.copyOf(ings);
    PlanKey.Builder keyBuilder = PlanKey.builder("bundle");
    keyBuilder.addInp(ingList);
    final PlanKey key = keyBuilder.build();

    Bundle<?> bundle = get(
        Bundle.class, key,
        new Supplier<Bundle<I>>() {
          @Override
          public Bundle<I> get() {
            return new Bundle<>(key, ingList);
          }
        });

    // Succeeds when key prefix spaces are disjoint for different types of
    // ingredients.
    Preconditions.checkState(ingList.equals(bundle.ings));

    // Sound when the above precondition passes.
    @SuppressWarnings("unchecked")
    Bundle<I> typedBundle = (Bundle<I>) bundle;

    return typedBundle;
  }


  /** An ingredient backed by a file which is hashable when the file exists. */
  public static final class FileIngredient extends Ingredient {
    /** THe backing file. */
    public final Source source;

    private FileIngredient(PlanKey key, Source source) {
      super(key);
      this.source = source;
    }

    @Override
    public Optional<Hash> hash() throws IOException {
      try {
        return Optional.of(Hash.hash(source));
      } catch (@SuppressWarnings("unused") FileNotFoundException ex) {
        return Optional.absent();
      }
    }
  }

  /** A group of files that need not be known at construct time. */
  public abstract class FileSetIngredient extends Ingredient {
    private Optional<ImmutableList<FileIngredient>> mainSources
        = Optional.absent();
    private Optional<ImmutableList<FileIngredient>> testSources
        = Optional.absent();
    private Optional<Hash> hash = Optional.absent();
    private MojoExecutionException problem;

    FileSetIngredient(PlanKey key) {
      super(key);
    }

    @Override
    public final synchronized Optional<Hash> hash() throws IOException {
      if (mainSources.isPresent() && testSources.isPresent()) {
        return Hash.hashAllHashables(
            ImmutableList.<FileIngredient>builder()
            .addAll(mainSources.get())
            .addAll(testSources.get()).build());
      } else {
        return Optional.absent();
      }
    }

    /** Source files that should contribute to the artifact. */
    public synchronized ImmutableList<FileIngredient> mainSources()
    throws MojoExecutionException {
      if (mainSources.isPresent()) {
        return mainSources.get();
      } else {
        throw getProblem();
      }
    }

    /** Source files that are used to test the artifact. */
    public synchronized ImmutableList<FileIngredient> testSources()
    throws MojoExecutionException {
      if (testSources.isPresent()) {
        return testSources.get();
      } else {
        throw getProblem();
      }
    }

    /** The reason the sources are not available. */
    protected final MojoExecutionException getProblem() {
      MojoExecutionException mee = problem;
      return (mee == null)
          ? new MojoExecutionException(key + " never set")
          : mee;
    }

    /**
     * May be called if inputs to {@link #setFiles} are unavailable due to an
     * exceptional condition.
     */
    protected void setProblem(Throwable th) {
      if (th instanceof MojoExecutionException) {
        problem = (MojoExecutionException) th;
      } else {
        problem = new MojoExecutionException("Could not determine " + key, th);
      }
    }

    protected final
    void setSources(
        Iterable<? extends FileIngredient> newMainSources,
        Iterable<? extends FileIngredient> newTestSources)
    throws IOException {
      Optional<ImmutableList<FileIngredient>> newMainSourceList = Optional.of(
          ImmutableList.copyOf(newMainSources));
      Optional<ImmutableList<FileIngredient>> newTestSourceList = Optional.of(
          ImmutableList.copyOf(newTestSources));
      Hash mainHash = Hash.hashAllHashables(newMainSourceList.get()).get();
      Hash testHash = Hash.hashAllHashables(newTestSourceList.get()).get();
      Optional<Hash> hashOfFiles = Optional.of(
          Hash.hashAllHashes(
              ImmutableList.<Hash>of(mainHash, testHash)));
      synchronized (this) {
        Preconditions.checkState(
            !mainSources.isPresent() && !testSources.isPresent());
        this.mainSources = newMainSourceList;
        this.testSources = newTestSourceList;
        this.hash = hashOfFiles;
      }
    }

    protected final
    void setMainSources(Iterable<? extends FileIngredient> sources) {
      Optional<ImmutableList<FileIngredient>> newSources = Optional.of(
          ImmutableList.copyOf(sources));
      synchronized (this) {
        Preconditions.checkState(!mainSources.isPresent());
        this.mainSources = newSources;
      }
    }

    /**
     * True iff the sources have been resolved to a concrete list
     * of extant files.
     */
    public synchronized boolean isResolved() {
      return hash.isPresent();
    }
  }

  /**
   * A file-set that can be explicitly set.   It's key does not
   * depend upon it's specification, so key uniqueness is the responsibility of
   * its creator.
   */
  public final class SettableFileSetIngredient extends FileSetIngredient {

    SettableFileSetIngredient(PlanKey key) {
      super(key);
    }

    /**
     * May be called once to specify the files in the set, as the file set
     * transitions from being used as an input to being used as an output.
     */
    public void setFiles(
        Iterable<? extends FileIngredient> newMainSources,
        Iterable<? extends FileIngredient> newTestSources)
    throws IOException {
      this.setSources(newMainSources, newTestSources);
    }

    @Override
    public void setProblem(Throwable th) {
      super.setProblem(th);
    }
  }

  /**
   * A set of files whose hash is the file paths, not their content, and
   * which is hashable when explicitly resolved.
   */
  public final class DirScanFileSetIngredient extends FileSetIngredient {
    private Sources.Finder finder;
    private final ImmutableList<File> mainRoots;
    private final ImmutableList<File> testRoots;
    private final ImmutableList<PathGlob> exclusions;

    private DirScanFileSetIngredient(PlanKey key, Sources.Finder finder) {
      super(key);
      this.finder = Preconditions.checkNotNull(finder);
      this.mainRoots = finder.mainRoots();
      this.testRoots = finder.testRoots();
      this.exclusions = finder.exclusions();
    }

    /** @see Sources.Finder#mainRoots */
    public ImmutableList<File> mainRoots() {
      return mainRoots;
    }

    /** @see Sources.Finder#testRoots */
    public ImmutableList<File> testRoots() {
      return testRoots;
    }

    /** @see Sources.Finder#exclusions */
    public ImmutableList<PathGlob> exclusions() {
      ImmutableList.Builder<PathGlob> b = ImmutableList.builder();
      for (PathGlob g : exclusions) {
        b.add(g);
      }
      return b.build();
    }

    /** Scans the file-system to find matching files. */
    public synchronized void resolve(Log log) throws IOException {
      if (isResolved()) {
        return;
      }
      Preconditions.checkNotNull(finder);
      try {
        Sources sources = finder.scan(log);
        ImmutableList<FileIngredient> mainSourceList = sortedSources(
            sources.mainFiles);
        ImmutableList<FileIngredient> testSourceList = sortedSources(
            sources.testFiles);
        this.setSources(mainSourceList, testSourceList);
      } catch (IOException ex) {
        setProblem(new MojoExecutionException(
            "Resolution of " + key + " failed", ex));
        throw ex;
      }
      this.finder = null;
    }
  }

  /**
   * Specifies how the compiler should interpret a group of source files and
   * where to look for those source files.
   */
  public static final class OptionsIngredient<T extends Options>
  extends Ingredient {
    private final T options;

    OptionsIngredient(PlanKey key, T options) {
      super(key);
      this.options = clone(options);
    }

    @Override
    public Optional<Hash> hash() throws NotSerializableException {
      return Optional.of(Hash.hashSerializable(options));
    }

    /**
     * An ID for the options which must be unique among a bundle of options
     * to the same compiler.
     */
    public String getId() { return options.getId(); }

    /**
     * A shallow copy of options since options are often mutable objects.
     */
    public T getOptions() {
      return clone(this.options);
    }

    /**
     * Runtime recast that the underlying options value has the given type.
     */
    public <ST extends Options>
    OptionsIngredient<ST> asSuperType(Class<ST> superType) {
      Preconditions.checkState(superType.isInstance(options));
      @SuppressWarnings("unchecked")
      OptionsIngredient<ST> casted = (OptionsIngredient<ST>) this;
      return casted;
    }

    private static <T extends Options> T clone(T options) {
      @SuppressWarnings("unchecked")
      Class<? extends T> optionsType =
          (Class<? extends T>) options.getClass();
      try {
        return optionsType.cast(options.clone());
      } catch (CloneNotSupportedException ex) {
        throw new IllegalArgumentException("Failed ot clone options", ex);
      }
    }
  }


  /**
   * An ingredient back by a file dedicated to hold a serialized object of
   * a specific type.  Reading and writing must be done explicitly and the
   * hash is of the version in memory.
   */
  public static final class SerializedObjectIngredient<T extends Serializable>
  extends Ingredient {

    /** The file containing the serialized content. */
    public final Source source;
    /** The type of objects that can be stored in the file. */
    public final Class<T> type;
    /** The stored instance if any. */
    private Optional<T> instance = Optional.absent();

    SerializedObjectIngredient(PlanKey key, Source source, Class<T> type) {
      super(key);
      this.source = source;
      this.type = type;
    }

    /** Runtime recast of the underlying object. */
    public <ST extends Serializable>
    SerializedObjectIngredient<ST> asSuperType(Class<ST> superType) {
      Preconditions.checkState(superType.isAssignableFrom(type));
      @SuppressWarnings("unchecked")
      SerializedObjectIngredient<ST> casted =
          (SerializedObjectIngredient<ST>) this;
      return casted;
    }

    @Override
    public Optional<Hash> hash() throws IOException {
      if (instance.isPresent()) {  // These had better be equivalent.
        return Optional.of(Hash.hashSerializable(instance.get()));
      }
      return Optional.absent();
    }

    /** Read the content of the file into memory. */
    public Optional<T> read() throws IOException {
      FileInputStream in;
      try {
        in = new FileInputStream(source.canonicalPath);
      } catch (@SuppressWarnings("unused") FileNotFoundException ex) {
        return Optional.absent();
      }
      try {
        Object deserialized;
        try (ObjectInputStream objIn = new ObjectInputStream(in)) {
          try {
            deserialized = objIn.readObject();
          } catch (ClassNotFoundException ex) {
            throw new IOException("Failed to deserialize", ex);
          }
          if (objIn.read() >= 0) {
            throw new IOException(
                "Extraneous content in serialized object file");
          }
        }
        instance = Optional.of(type.cast(deserialized));
        return instance;
      } finally {
        in.close();
      }
    }

    /**
     * The in-memory version of the object if it has been {@link #read read}
     * or {@link #setStoredObject stored}.
     */
    public Optional<T> getStoredObject() {
      return instance;
    }

    /**
     * Sets the in-memory instance.
     */
    public void setStoredObject(T instance) {
      this.instance = Optional.of(instance);
    }

    /**
     * Writes the in-memory instance to the {@link #source persisting file}.
     */
    public void write() throws IOException {
      Preconditions.checkState(instance.isPresent());
      source.canonicalPath.getParentFile().mkdirs();
      try (FileOutputStream out = new FileOutputStream(source.canonicalPath)) {
        try (ObjectOutputStream objOut = new ObjectOutputStream(out)) {
          objOut.writeObject(instance.get());
        }
      }
    }
  }

  ImmutableList<FileIngredient>
  sortedSources(Iterable<? extends Source> sources) {
    List<FileIngredient> hashedSources = Lists.newArrayList();
    for (Source s : sources) {
      hashedSources.add(file(s));
    }
    Collections.sort(
        hashedSources,
        new Comparator<FileIngredient>() {
          @Override
          public int compare(FileIngredient a, FileIngredient b) {
            return a.source.canonicalPath.compareTo(b.source.canonicalPath);
          }
        });
    return ImmutableList.copyOf(hashedSources);
  }

  /** An ingredient that represents a fixed string. */
  public static final class StringValue extends Ingredient {
    /** The fixed string value. */
    public final String value;
    StringValue(PlanKey key, String value) {
      super(key);
      this.value = value;
    }
    @Override
    public Optional<Hash> hash() throws IOException {
      return Optional.of(Hash.hashString(value));
    }

    @Override
    public String toString() {
      return "{StringValue " + value + "}";
    }
  }

  /**
   * An ingredient that represents a path, and hashes to a hash of that
   * path, not the content of the file referred to by that path.
   */
  public static final class PathValue extends Ingredient {
    /** The fixed path value. */
    public final File value;

    PathValue(PlanKey key, File value) {
      super(key);
      this.value = value;
    }
    @Override
    public Optional<Hash> hash() throws IOException {
      return Optional.of(Hash.hashString(value.getPath()));
    }

    @Override
    public String toString() {
      return "{PathValue " + value.getPath() + "}";
    }
  }

  /**
   * An ingredient that represents a URI, and hashes to a hash of that
   * URI's text, not the content referred to by that URI.
   */
  public static final class UriValue extends Ingredient {
    /** The fixed URI value. */
    public final URI value;

    UriValue(PlanKey key, URI value) {
      super(key);
      this.value = value;
    }
    @Override
    public Optional<Hash> hash() throws IOException {
      return Optional.of(Hash.hashString(value.toString()));
    }

    @Override
    public String toString() {
      return "{UriValue " + value.toString() + "}";
    }
  }

  /**
   * A bundle of ingredients.
   */
  public static final class Bundle<I extends Ingredient> extends Ingredient {
    /** The constituent ingredients. */
    public final ImmutableList<I> ings;

    Bundle(PlanKey key, Iterable<? extends I> ings) {
      super(key);
      this.ings = ImmutableList.copyOf(ings);
    }

    /** Type coercion. */
    public <J extends Ingredient> Bundle<J> asSuperType(
        Function<? super Ingredient, ? extends J> typeCheckingIdentityFn) {
      for (Ingredient ing : ings) {
        J typedIng = typeCheckingIdentityFn.apply(ing);
        Preconditions.checkState(ing == typedIng);
      }
      @SuppressWarnings("unchecked")
      // Sound when typeCheckingIdentityFn is sound.
      Bundle<J> typedBundle = (Bundle<J>) this;
      return typedBundle;
    }

    @Override
    public Optional<Hash> hash() throws IOException {
      ImmutableList.Builder<Hash> hashes = ImmutableList.builder();
      for (Ingredient ing : ings) {
        Optional<Hash> h = ing.hash();
        if (h.isPresent()) {
          hashes.add(h.get());
        } else {
          return Optional.absent();
        }
      }
      return Optional.of(Hash.hashAllHashes(hashes.build()));
    }
  }
}
