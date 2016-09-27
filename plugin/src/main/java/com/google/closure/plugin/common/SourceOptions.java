package com.google.closure.plugin.common;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.google.closure.plugin.plan.PlanContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

/**
 * Options that specify how to find sources.
 */
@SuppressWarnings("serial")
public abstract class SourceOptions extends Options {
  /**
   * Source file roots.
   */
  public SourceRootBuilder[] source;

  /**
   * Test file roots.
   */
  public SourceRootBuilder[] testSource;

  /**
   * Add a path patterns to include specified as ANT-directory-scanner-style
   * patterns like <code>**<!---->/*.ext</code>.
   */
  public void setInclude(String filePattern) {
    this.include.add(filePattern);
  }
  private final List<String> include = Lists.newArrayList();

  /**
   * Path patterns to include specified as ANT-directory-scanner-style patterns
   * like <code>**<!---->/*.ext</code>.
   */
  public ImmutableList<String> getIncludes() {
    return ImmutableList.copyOf(include);
  }

  /**
   * Add a path patterns to exclude specified as ANT-directory-scanner-style
   * patterns like <code>**<!---->/*.ext</code>.
   */
  public void setExclude(String filePattern) {
    this.exclude.add(filePattern);
  }
  private final List<String> exclude = Lists.newArrayList();
  /**
   * Path patterns to exclude specified as ANT-directory-scanner-style patterns
   * like <code>**<!---->/*.ext</code>.
   */
  public ImmutableList<String> getExcludes() {
    return ImmutableList.copyOf(exclude);
  }


  /** Snapshots. */
  public final DirectoryScannerSpec toDirectoryScannerSpec(PlanContext c) {
    ImmutableSet.Builder<TypedFile> allRoots = ImmutableSet.builder();

    if (source != null) {
      for (SourceRootBuilder oneSource : source) {
        allRoots.add(oneSource.build());
      }
    }
    if (testSource != null) {
      for (SourceRootBuilder oneSource : testSource) {
        allRoots.add(oneSource.build(SourceFileProperty.TEST_ONLY));
      }
    }

    ImmutableList<FileExt> sourceExtensions = sourceExtensions();
    for (FileExt ext : sourceExtensions) {
      if (source == null || source.length == 0) {
        allRoots.add(c.srcfilesDirs.getDefaultProjectSourceDirectory(ext));
      }
      if (testSource == null || testSource.length == 0) {
        allRoots.add(
            c.srcfilesDirs.getDefaultProjectSourceDirectory(
                ext, SourceFileProperty.TEST_ONLY));
      }

      for (EnumSet<SourceFileProperty> subset
           : subsetsOf(EnumSet.allOf(SourceFileProperty.class))) {
        allRoots.add(
            new TypedFile(
                c.genfilesDirs.getGeneratedSourceDirectory(ext, subset),
                subset));
      }
    }

    ImmutableList.Builder<String> allIncludes = ImmutableList.builder();
    if (!include.isEmpty()) {
      allIncludes.addAll(include);
    } else {
      for (FileExt sourceExtension : sourceExtensions) {
        for (String suffix : sourceExtension.allSuffixes()) {
          allIncludes.add("**/*." + suffix);
        }
      }
    }

    // ANT defaults added later.
    ImmutableList<String> allExcludes = getExcludes();

    return new DirectoryScannerSpec(
        allRoots.build(), allIncludes.build(), allExcludes);
  }

  /**
   * Source extensions used to compute default includes.
   * For example, a source extension of {@code "js"} implies a default include
   * of <code>**<!---->/*.js</code>.
   */
  protected abstract ImmutableList<FileExt> sourceExtensions();


  /**
   * A plexus-configurable source root.
   */
  public static final class SourceRootBuilder implements Serializable {
    private File root;
    private final EnumSet<SourceFileProperty> props =
        EnumSet.noneOf(SourceFileProperty.class);

    /** The default string form just specifies the file. */
    public void set(File f) {
      setRoot(f);
    }

    /** The root directory under which to search. */
    public void setRoot(File f) {
      if (f.exists() && !f.isDirectory()) {
        throw new IllegalArgumentException(f + " is not a directory");
      }
      this.root = f;
    }

    /** Adds the file properties to those already specified. */
    public void setFileProperty(SourceFileProperty... properties) {
      props.addAll(Arrays.asList(properties));
    }

    /**
     * Whether the source files found under this root are only used when needed
     * to satisfy dependencies.
     */
    public void setLoadAsNeeded(boolean b) {
      setProperty(b, SourceFileProperty.LOAD_AS_NEEDED);
    }

    /**
     * Whether the source files found under this root are used only in testing.
     */
    public void setTestOnly(boolean b) {
      setProperty(b, SourceFileProperty.TEST_ONLY);
    }

    private void setProperty(boolean b, SourceFileProperty p) {
      if (b) {
        props.add(p);
      } else {
        props.remove(p);
      }
    }

    /** An immutable snapshot of the current state. */
    public TypedFile build(SourceFileProperty... implied) {
      if (root == null) {
        throw new IllegalArgumentException(
            "Must specify a root directory");
      }
      EnumSet<SourceFileProperty> allProps = EnumSet.copyOf(this.props);
      allProps.addAll(Arrays.asList(implied));
      return new TypedFile(root, allProps);
    }
  }


  private static <T extends Enum<T>>
  Iterable<EnumSet<T>> subsetsOf(EnumSet<T> set) {
    if (set.isEmpty()) {
      return ImmutableList.of(EnumSet.copyOf(set));
    }
    T first = set.iterator().next();
    // Sound because all enum instances allowed in EnumSet are final.
    @SuppressWarnings("unchecked")
    final Class<T> typ = (Class<T>) first.getClass();
    // Sound because typ is a reference type so T[] is an Object[].
    @SuppressWarnings("unchecked")
    final T[] els = (T[]) Array.newInstance(typ, set.size());

    set.toArray(els);

    Preconditions.checkState(els.length <= 63);  // Assume no overflow.
    final long n = 1L << els.length;

    return new Iterable<EnumSet<T>>() {

      @Override
      public Iterator<EnumSet<T>> iterator() {
        return new Iterator<EnumSet<T>>() {
          private long idx = 0;

          @Override
          public boolean hasNext() {
            return idx != n;  // Handles underflow of n
          }

          @Override
          public EnumSet<T> next() {
            if (!hasNext()) { throw new NoSuchElementException(); }
            EnumSet<T> es = EnumSet.noneOf(typ);
            for (int i = 0; i < els.length; ++i) {
              if ((idx & (1L << i)) != 0) {
                es.add(els[i]);
              }
            }
            ++idx;
            return es;
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }
}
